package dfarium.mapartisanconnector.litematica;

import dfarium.mapartisanconnector.structure.Bounds;
import dfarium.mapartisanconnector.structure.BuildStructure;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.malilib.interfaces.ICompletionListener;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter that applies construction snapshots through Litematica (spec section 5.2).
 *
 * Every revision gets a fresh schematic and placement covering the union of the previous and
 * new bounds, so positions the new revision leaves empty are pasted as air.
 * Only one paste runs at a time. All methods run on the client thread except onServerStopping.
 */
public final class LitematicaPaster {
    private static final Logger LOGGER = LoggerFactory.getLogger("MapartisanConnector");
    private static final String REGION = "mapartisan";
    private static final String PLACEMENT_NAME = "MapArtisan preview";
    private static final long STALL_WARNING_NANOS = 30_000_000_000L;
    private static final String WORLD_CLOSED = "mundo cerrado durante el pegado";

    public interface Listener {
        void onApplied(PasteReport report);

        void onAborted(PasteReport report);
    }

    /** Timings in milliseconds; wait is the time until the first chunk was ready to paste. */
    public record PasteReport(String structureName, Bounds volume, int chunks, int retries,
                              long totalMillis, long waitMillis, long pasteMillis, String abortReason) {
    }

    private record ActivePaste(MapArtisanPasteTask task, BuildStructure structure, Bounds target,
                               Bounds volume, long scheduledAt, Listener listener) {
    }

    private final Minecraft mc = Minecraft.getInstance();

    private BlockPos origin;
    private ResourceKey<Level> dimension;
    // Area relative to the origin that may still hold blocks from earlier pastes.
    private Bounds dirty;
    private SchematicPlacement placement;
    // Written on the client thread; read by onServerStopping on the server thread.
    private volatile ActivePaste active;
    private BuildStructure lastApplied;
    private Bounds lastVolume;
    private boolean needsResync;

    private int lastSeenPending = -1;
    private long stallSince;
    private boolean stallWarned;

    public void setOrigin(BlockPos origin, ResourceKey<Level> dimension) {
        requireIdle();
        // A new origin starts a new reserved area; blocks pasted in the previous one stay in the world.
        resetSession(true);
        this.origin = origin.immutable();
        this.dimension = dimension;
    }

    /** Starts pasting the structure. Throws IllegalStateException when the paste cannot start. */
    public void paste(BuildStructure structure, Listener listener) {
        requireIdle();
        if (origin == null) {
            throw new IllegalStateException("Primero define el origen con /mapartisan test origin");
        }
        if (!mc.hasSingleplayerServer() || mc.level == null || mc.player == null) {
            throw new IllegalStateException("Solo funciona en un mundo singleplayer");
        }
        if (!mc.level.dimension().equals(dimension)) {
            throw new IllegalStateException("El origen pertenece a otra dimensión");
        }
        if (!mc.player.isCreative()) {
            throw new IllegalStateException("Hace falta modo creativo");
        }

        Bounds target = Bounds.of(structure);
        Bounds volume = dirty == null ? target : dirty.union(target);
        checkBuildHeight(volume);

        SchematicPlacement next = SchematicPlacement.createFor(buildSchematic(structure, volume),
                origin.offset(volume.minX(), volume.minY(), volume.minZ()), PLACEMENT_NAME, true, true);
        next.setShouldBeSaved(false);
        replacePlacement(next);

        MapArtisanPasteTask task = new MapArtisanPasteTask(next);
        ActivePaste paste = new ActivePaste(task, structure, target, volume, System.nanoTime(), listener);
        task.setCompletionListener(new ICompletionListener() {
            @Override
            public void onTaskCompleted() {
                finish(paste, null);
            }

            @Override
            public void onTaskAborted() {
                finish(paste, mc.level == null ? WORLD_CLOSED : "tarea retirada del scheduler de Litematica");
            }
        });

        // From here on any part of the volume may have been modified.
        dirty = volume;
        active = paste;
        lastSeenPending = -1;
        stallWarned = false;
        stallSince = System.nanoTime();
        TaskScheduler.getInstanceServer().scheduleTask(task, 1);
        LOGGER.info("Scheduled paste of {} at {} volume {} ({} positions)", structure.name(), origin, volume, structure.count());
    }

    /** Removes the active task; Litematica's listener then reports the abort. */
    public boolean abort() {
        ActivePaste paste = active;
        if (paste == null) {
            return false;
        }
        cancel(paste.task());
        return true;
    }

    /**
     * Detected from the client tick rather than Fabric's DISCONNECT event, which runs on a
     * Netty thread. Minecraft.disconnect drops the client task queue, so Litematica's
     * completion listener (delivered through Minecraft.execute) may never arrive: report here.
     */
    private void onWorldClosed() {
        ActivePaste paste = active;
        if (paste != null) {
            cancel(paste.task());
            finish(paste, WORLD_CLOSED);
        }
        LOGGER.info("World closed; MapArtisan session reset");
        // Litematica clears its placements on world change; just forget ours.
        placement = null;
        resetSession(false);
    }

    /** Called on the integrated server thread. Litematica does not clear its server scheduler itself. */
    public void onServerStopping() {
        ActivePaste paste = active;
        if (paste != null) {
            LOGGER.info("Server stopping during the paste of {}; cancelling the task", paste.structure().name());
            cancel(paste.task());
        }
    }

    /** Client tick: invalidates the session when the world closes or the dimension changes, and warns about stalled pastes. */
    public void tick(Minecraft client) {
        if (client.level == null) {
            if (origin != null || active != null) {
                onWorldClosed();
            }
            return;
        }
        ActivePaste paste = active;
        if (paste == null) {
            return;
        }
        if (!client.level.dimension().equals(dimension)) {
            cancel(paste.task());
            finish(paste, "cambio de dimensión");
            resetSession(true);
            message("§cCambio de dimensión: sesión invalidada.");
            return;
        }

        MapArtisanPasteTask task = paste.task();
        long now = System.nanoTime();
        int pending = task.pendingCount();
        // A paused game does not tick the integrated server; that is waiting, not stalling.
        if (pending != lastSeenPending || client.isPaused()) {
            lastSeenPending = pending;
            stallSince = now;
            stallWarned = false;
        } else if (!stallWarned && now - stallSince > STALL_WARNING_NANOS) {
            stallWarned = true;
            String reason = task.blockedReason();
            LOGGER.warn("Paste stalled for 30 s with {} chunks pending: {}", pending, reason);
            message("§ePegado sin avance hace 30 s (" + pending + " chunks pendientes): " + reason);
        }
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("Origen: ").append(origin == null ? "sin definir" : origin.toShortString());
        sb.append(" | Última aplicada: ").append(lastApplied == null ? "ninguna" : lastApplied.name());
        if (needsResync) {
            sb.append(" | §crequiere resincronización§r");
        }
        ActivePaste paste = active;
        if (paste == null) {
            sb.append(" | Sin pegado activo");
        } else {
            MapArtisanPasteTask task = paste.task();
            sb.append(" | Pegando ").append(paste.structure().name()).append(": ")
                    .append(task.pendingCount()).append('/').append(task.totalChunks())
                    .append(" chunks pendientes, reintentos ").append(task.retries());
            String reason = task.blockedReason();
            if (reason != null) {
                sb.append(" | Bloqueado: ").append(reason);
            }
        }
        return sb.toString();
    }

    public boolean isPasting() {
        return active != null;
    }

    /** Pending and total chunks of the active paste; -1 before the task starts or when idle. */
    public int activePendingChunks() {
        ActivePaste paste = active;
        return paste == null ? -1 : paste.task().pendingCount();
    }

    public int activeTotalChunks() {
        ActivePaste paste = active;
        return paste == null ? -1 : paste.task().totalChunks();
    }

    public String activeBlockedReason() {
        ActivePaste paste = active;
        return paste == null ? null : paste.task().blockedReason();
    }

    public BlockPos origin() {
        return origin;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BuildStructure lastApplied() {
        return lastApplied;
    }

    /** Volume of the last successful paste, relative to the origin. */
    public Bounds lastVolume() {
        return lastVolume;
    }

    private static void cancel(MapArtisanPasteTask task) {
        task.cancel();
        TaskScheduler.getInstanceServer().removeTask(task);
    }

    /** Reports a paste exactly once; abortReason is null for a completed paste. */
    private void finish(ActivePaste paste, String abortReason) {
        if (active != paste) {
            // Already reported, or the session was reset.
            return;
        }
        active = null;

        MapArtisanPasteTask task = paste.task();
        boolean applied = abortReason == null && task.isFinished();
        long now = System.nanoTime();
        long ready = task.firstReadyNanos();
        long totalMillis = (now - paste.scheduledAt()) / 1_000_000L;
        long waitMillis = ready == 0 ? totalMillis : (ready - paste.scheduledAt()) / 1_000_000L;
        PasteReport report = new PasteReport(paste.structure().name(), paste.volume(), task.totalChunks(),
                task.retries(), totalMillis, waitMillis, totalMillis - waitMillis, applied ? null : abortReason);
        LOGGER.info("Paste of {} {}: {}", paste.structure().name(), applied ? "applied" : "aborted", report);

        if (applied) {
            lastApplied = paste.structure();
            lastVolume = paste.volume();
            // Everything outside the new structure is air now.
            dirty = paste.target();
            needsResync = false;
            paste.listener().onApplied(report);
        } else {
            // The volume may be partially pasted; the next paste must cover all of it again.
            needsResync = true;
            paste.listener().onAborted(report);
        }
    }

    private LitematicaSchematic buildSchematic(BuildStructure structure, Bounds volume) {
        AreaSelection area = new AreaSelection();
        area.setName(structure.name());
        area.setExplicitOrigin(BlockPos.ZERO);
        area.addSubRegionBox(new Box(BlockPos.ZERO,
                new BlockPos(volume.sizeX() - 1, volume.sizeY() - 1, volume.sizeZ() - 1), REGION), false);

        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "MapArtisan");
        if (schematic == null) {
            throw new IllegalStateException("Litematica no pudo crear el esquema");
        }

        // Positions without a block stay air, which clears whatever earlier revisions left there.
        LitematicaBlockStateContainer container = schematic.getSubRegionContainer(REGION);
        for (int i = 0; i < structure.count(); i++) {
            container.set(structure.x(i) - volume.minX(), structure.y(i) - volume.minY(),
                    structure.z(i) - volume.minZ(), structure.state(i));
        }
        return schematic;
    }

    private void replacePlacement(SchematicPlacement next) {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement selected = manager.getSelectedSchematicPlacement();
        boolean oursWasSelected = selected != null && selected == placement;

        if (placement != null) {
            manager.removeSchematicPlacement(placement);
        }
        manager.addSchematicPlacement(next, false);
        // addSchematicPlacement selects the new placement when nothing is selected;
        // keep the user's selection as it was.
        manager.setSelectedSchematicPlacement(oursWasSelected ? next : selected);
        placement = next;
    }

    private void checkBuildHeight(Bounds volume) {
        int minY = origin.getY() + volume.minY();
        int maxY = origin.getY() + volume.maxY();
        if (mc.level.isOutsideBuildHeight(minY) || mc.level.isOutsideBuildHeight(maxY)) {
            throw new IllegalStateException("El volumen Y " + minY + ".." + maxY + " no cabe en el mundo ("
                    + mc.level.getMinY() + ".." + mc.level.getMaxY() + ")");
        }
    }

    private void requireIdle() {
        if (active != null) {
            throw new IllegalStateException("Hay un pegado activo; espera a que termine o usa /mapartisan test abort");
        }
    }

    private void resetSession(boolean removePlacement) {
        if (removePlacement && placement != null) {
            DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
        }
        placement = null;
        active = null;
        origin = null;
        dimension = null;
        dirty = null;
        lastApplied = null;
        lastVolume = null;
        needsResync = false;
    }

    private void message(String text) {
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(text));
        }
    }
}
