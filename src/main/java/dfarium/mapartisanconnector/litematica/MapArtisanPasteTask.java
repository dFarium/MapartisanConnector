package dfarium.mapartisanconnector.litematica;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.scheduler.tasks.TaskPasteSchematicPerChunkBase;
import fi.dy.masa.litematica.schematic.placement.PlacementManagerDaemonHandler;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.util.PasteLayerBehavior;
import fi.dy.masa.litematica.util.ReplaceBehavior;
import fi.dy.masa.litematica.util.SchematicPlacingUtils;
import fi.dy.masa.malilib.util.position.LayerRange;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-player paste task based on Litematica's TaskPasteSchematicPerChunkDirect.
 *
 * Differences: the replace and layer behaviours are fixed instead of read from the user's
 * Litematica config, no messages are shown, and progress and stall causes are exposed.
 * execute() runs on the integrated server thread; the getters may be read from the client thread.
 */
public final class MapArtisanPasteTask extends TaskPasteSchematicPerChunkBase {
    // Same per-tick budget as TaskPasteSchematicPerChunkDirect, vanilla tick time included.
    private static final long TICK_BUDGET_NANOS = 60_000_000L;

    private final ArrayListMultimap<ChunkPos, SchematicPlacement> placementsPerChunk = ArrayListMultimap.create();
    private volatile int totalChunks = -1;
    private volatile int pendingCount = -1;
    private volatile int retries;
    private volatile long firstReadyNanos;
    private volatile boolean cancelled;
    private volatile String blockedReason = "waiting for the first server tick";

    public MapArtisanPasteTask(SchematicPlacement placement) {
        super(List.of(placement), new LayerRange(null), false);
    }

    @Override
    public void init() {
        if (cancelled) {
            return;
        }
        super.init();
        totalChunks = pendingChunks.size();
        pendingCount = totalChunks;
    }

    /**
     * Makes the scheduler drop this task. TaskScheduler.removeTask misses tasks still queued in
     * tasksToAdd (scheduled but not yet run, e.g. when the game paused right away); those are
     * removed on their first scheduler pass through shouldRemove().
     */
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean shouldRemove() {
        return cancelled || super.shouldRemove();
    }

    @Override
    public boolean canExecute() {
        return !cancelled && super.canExecute() && mc.hasSingleplayerServer() && world != null && !world.isClientSide();
    }

    @Override
    public boolean execute(ProfilerFiller profiler) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            // finished stays false, so the listener reports an abort.
            return true;
        }

        long vanillaTickNanos = server.getTickTimesNanos()[server.getTickCount() % 100];
        long start = System.nanoTime();
        int processed = 0;

        sortChunkList();
        for (int i = 0; i < pendingChunks.size(); i++) {
            if (vanillaTickNanos + (System.nanoTime() - start) >= TICK_BUDGET_NANOS) {
                break;
            }
            ChunkPos pos = pendingChunks.get(i);
            if (!canProcessChunk(pos)) {
                continue;
            }
            if (firstReadyNanos == 0) {
                firstReadyNanos = System.nanoTime();
            }
            if (processChunk(pos)) {
                pendingChunks.remove(i--);
                processed++;
            }
        }
        pendingCount = pendingChunks.size();

        if (pendingChunks.isEmpty()) {
            finished = true;
            blockedReason = null;
            return true;
        }

        blockedReason = processed == 0 ? diagnose(pendingChunks.getFirst()) : null;
        updateInfoHudLines();
        return false;
    }

    @Override
    protected void onChunkAddedForHandling(ChunkPos pos, SchematicPlacement placement) {
        super.onChunkAddedForHandling(pos, placement);
        placementsPerChunk.put(pos, placement);
    }

    @Override
    protected boolean processChunk(ChunkPos pos) {
        for (SchematicPlacement placement : new ArrayList<>(placementsPerChunk.get(pos))) {
            // ALL also writes the schematic's air, which clears blocks left by earlier revisions.
            if (SchematicPlacingUtils.placeToWorldWithinChunk(world, pos, placement,
                    ReplaceBehavior.ALL, PasteLayerBehavior.ALL, false)) {
                placementsPerChunk.remove(pos, placement);
            } else {
                retries++;
            }
        }
        return !placementsPerChunk.containsKey(pos);
    }

    @Override
    protected void onStop() {
        InfoHud.getInstance().removeInfoHudRenderer(this, false);
        super.onStop();
    }

    /** Mirrors the conditions of TaskPasteSchematicPerChunkBase.canProcessChunk. */
    private String diagnose(ChunkPos pos) {
        if (!schematicWorld.getChunkSource().hasChunk(pos.x(), pos.z())) {
            return "the hologram has not loaded chunk " + pos + " yet (player too far?)";
        }
        if (PlacementManagerDaemonHandler.INSTANCE.hasAnyRebuildTasksFor(pos)) {
            return "Litematica is rebuilding the hologram of chunk " + pos;
        }
        if (!areSurroundingChunksLoaded(pos, clientWorld, 1)) {
            return "client chunks around " + pos + " are not loaded (player too far)";
        }
        if (retries > 0) {
            return "placeToWorldWithinChunk returned false " + retries + " times";
        }
        return "no identified cause in chunk " + pos;
    }

    public int totalChunks() {
        return totalChunks;
    }

    public int pendingCount() {
        return pendingCount;
    }

    public int retries() {
        return retries;
    }

    /** When the first chunk became ready to paste, or 0. */
    public long firstReadyNanos() {
        return firstReadyNanos;
    }

    public String blockedReason() {
        return blockedReason;
    }

    public boolean isFinished() {
        return finished;
    }
}
