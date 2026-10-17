package dfarium.mapartisanconnector.test;

import dfarium.mapartisanconnector.litematica.LitematicaPaster;
import dfarium.mapartisanconnector.structure.Bounds;
import dfarium.mapartisanconnector.structure.BuildStructure;
import dfarium.mapartisanconnector.verify.Verification;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs the stage 1 checks in sequence (/mapartisan test suite) and logs every result with
 * the "[suite]" prefix. Leaving the world or pausing the game must still be tested by hand.
 * Everything runs on the client thread; waits are driven by tick().
 */
public final class TestSuite {
    private static final Logger LOGGER = LoggerFactory.getLogger("MapartisanConnector");
    private static final int TICKS_PER_SECOND = 20;
    private static final int PASTE_TIMEOUT_SECONDS = 60;
    private static final int NEIGHBOR_TEST_ROWS = 16;

    /** An expected-result mismatch: recorded as a failure, the suite continues. */
    private static final class CheckFailed extends RuntimeException {
        CheckFailed(String message) {
            super(message);
        }
    }

    private record Step(String name, Supplier<CompletableFuture<String>> action) {
    }

    private record Waiter(BooleanSupplier condition, long deadline, CompletableFuture<Boolean> future) {
    }

    private record Outcome(boolean applied, LitematicaPaster.PasteReport report) {
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final LitematicaPaster paster;
    private final List<Waiter> waiters = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    private final List<String> summary = new ArrayList<>();
    private List<Step> steps = List.of();
    private boolean running;
    private long ticks;
    private long suiteStart;
    private int failures;

    public TestSuite(LitematicaPaster paster) {
        this.paster = paster;
    }

    public boolean isRunning() {
        return running;
    }

    public void start() {
        if (running) {
            throw new IllegalStateException("The suite is already running");
        }
        if (paster.origin() == null) {
            throw new IllegalStateException("Set the origin first with /mapartisan test origin");
        }
        if (paster.isPasting()) {
            throw new IllegalStateException("A paste is running");
        }

        steps = List.of(
                new Step("A + verification; the Litematica selection does not change", this::pasteAKeepsSelection),
                new Step("B removes tall blocks and supports", () -> pasteAndVerify("b")),
                new Step("A again", () -> pasteAndVerify("a")),
                new Step("flat (2D)", () -> pasteAndVerify("flat")),
                new Step("max (maximum volume)", () -> pasteAndVerify("max")),
                new Step("A after max (removes the staircase)", () -> pasteAndVerify("a")),
                new Step("Neighbor updates do not change the pasted blocks", this::neighborUpdates),
                new Step("Abort before the first tick", this::abortImmediately),
                new Step("Resync with A after the abort", () -> pasteAndVerify("a")),
                new Step("Abort halfway through the paste", this::abortMidPaste),
                new Step("Resync with B after the abort", () -> pasteAndVerify("b")),
                new Step("A foreign selection is kept", this::foreignSelection),
                new Step("Litematica rendering disabled (observation)", this::renderingDisabled),
                new Step("Persistent leaves and a stable area after 30 s", this::stableAfterWait),
                // Last: it moves the origin away and back, which resets the session.
                new Step("Area without loaded chunks: wait, watchdog and abort", this::unloadedArea));

        running = true;
        failures = 0;
        summary.clear();
        suiteStart = System.nanoTime();
        log("Start: " + steps.size() + " steps, origin " + paster.origin().toShortString()
                + ". Stay in the middle of the area and do not pause the game.");
        runStep(0);
    }

    /**
     * Client tick: resolves waits and stops the suite when the world closes. Must run before
     * LitematicaPaster.tick so its world-closed abort reports are not taken as step results.
     */
    public void tick(Minecraft client) {
        if (!running) {
            return;
        }
        if (client.level == null) {
            stop("world closed");
            return;
        }
        ticks++;
        for (Waiter waiter : new ArrayList<>(waiters)) {
            if (waiter.condition().getAsBoolean()) {
                waiters.remove(waiter);
                waiter.future().complete(true);
            } else if (ticks >= waiter.deadline()) {
                waiters.remove(waiter);
                waiter.future().complete(false);
            }
        }
    }

    private void runStep(int index) {
        if (!running) {
            return;
        }
        if (index >= steps.size()) {
            finish();
            return;
        }
        Step step = steps.get(index);
        String label = (index + 1) + "/" + steps.size() + " " + step.name();
        log("▶ " + label);
        long start = System.nanoTime();

        CompletableFuture<String> future;
        try {
            future = step.action().get();
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }

        future.whenComplete((detail, error) -> {
            if (!running) {
                return;
            }
            long millis = (System.nanoTime() - start) / 1_000_000L;
            if (error == null) {
                record("§aOK§r", label, detail, millis);
                runStep(index + 1);
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            failures++;
            record("§cFAIL§r", label, cause.getMessage(), millis);
            if (cause instanceof CheckFailed && !paster.isPasting()) {
                runStep(index + 1);
            } else {
                LOGGER.error("[suite] Unexpected failure in step {}", label, cause);
                stop("unexpected failure");
            }
        });
    }

    // --- Steps ---

    private CompletableFuture<String> pasteAKeepsSelection() {
        SchematicPlacement selected = manager().getSelectedSchematicPlacement();
        return pasteAndVerify("a").thenApply(detail -> {
            if (manager().getSelectedSchematicPlacement() != selected) {
                throw new CheckFailed("the Litematica selection changed; " + detail);
            }
            return detail + "; selection unchanged (" + (selected == null ? "none" : selected.getName()) + ")";
        });
    }

    private CompletableFuture<String> neighborUpdates() {
        IntegratedServer server = mc.getSingleplayerServer();
        BuildStructure structure = paster.lastApplied();
        Bounds volume = paster.lastVolume();
        BlockPos origin = paster.origin();
        ResourceKey<Level> dimension = paster.dimension();
        if (server == null || structure == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No revision has been applied"));
        }

        // Highest block of the sand, carpet and leaves columns in the first rows.
        int[][] top = new int[3][NEIGHBOR_TEST_ROWS + 1];
        for (int[] column : top) {
            Arrays.fill(column, -1);
        }
        for (int i = 0; i < structure.count(); i++) {
            int x = structure.x(i);
            int z = structure.z(i);
            if (x <= 2 && z >= 1 && z <= NEIGHBOR_TEST_ROWS) {
                top[x][z] = Math.max(top[x][z], structure.y(i));
            }
        }

        CompletableFuture<Integer> touched = new CompletableFuture<>();
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            int count = 0;
            if (level != null) {
                for (int x = 0; x <= 2; x++) {
                    for (int z = 1; z <= NEIGHBOR_TEST_ROWS; z++) {
                        int y = top[x][z] + 1;
                        if (top[x][z] < 0 || y > volume.maxY()) {
                            continue;
                        }
                        // Placing and removing a block above notifies the pasted block below.
                        BlockPos pos = origin.offset(x, y, z);
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        count++;
                    }
                }
            }
            int result = count;
            mc.execute(() -> touched.complete(result));
        });

        return touched.thenCompose(count -> verify().thenApply(v ->
                count + " blocks updated on top of sand, carpet and leaves; " + v));
    }

    private CompletableFuture<String> abortImmediately() {
        CompletableFuture<Outcome> paste = startPaste("max");
        paster.abort();
        return paste.thenApply(o -> {
            if (o.applied()) {
                throw new CheckFailed("max was applied despite the immediate abort");
            }
            return "aborted (" + o.report().abortReason() + ") after " + o.report().totalMillis() + " ms";
        });
    }

    private CompletableFuture<String> abortMidPaste() {
        CompletableFuture<Outcome> paste = startPaste("max");
        int[] pendingAtAbort = {-1, -1};
        return waitUntil(() -> {
            int pending = paster.activePendingChunks();
            int total = paster.activeTotalChunks();
            return paste.isDone() || (pending > 0 && pending < total);
        }, 10 * TICKS_PER_SECOND).thenCompose(ignored -> {
            if (!paste.isDone()) {
                pendingAtAbort[0] = paster.activePendingChunks();
                pendingAtAbort[1] = paster.activeTotalChunks();
                paster.abort();
            }
            return paste;
        }).thenApply(o -> {
            if (o.applied()) {
                // Not a failure: the paste can finish in fewer ticks than it takes to observe progress.
                return "OBSERVATION: max finished before it could be aborted (" + describe(o.report()) + ")";
            }
            return "aborted with " + pendingAtAbort[0] + "/" + pendingAtAbort[1] + " chunks pending ("
                    + o.report().abortReason() + ")";
        });
    }

    private CompletableFuture<String> foreignSelection() {
        SchematicPlacementManager manager = manager();
        SchematicPlacement previous = manager.getSelectedSchematicPlacement();
        SchematicPlacement foreign = createForeignPlacement();
        manager.addSchematicPlacement(foreign, false);
        manager.setSelectedSchematicPlacement(foreign);
        Runnable cleanup = () -> {
            manager.removeSchematicPlacement(foreign);
            manager.setSelectedSchematicPlacement(previous);
        };
        cleanups.add(cleanup);

        return pasteAndVerify("a").thenApply(detail -> {
            SchematicPlacement selected = manager.getSelectedSchematicPlacement();
            boolean listed = manager.getAllSchematicsPlacements().contains(foreign);
            if (selected != foreign || !listed) {
                throw new CheckFailed("foreign placement " + (listed ? "present" : "removed")
                        + ", selected: " + (selected == null ? "none" : selected.getName()));
            }
            return detail + "; foreign placement intact and selected";
        }).whenComplete((detail, error) -> {
            if (cleanups.remove(cleanup)) {
                cleanup.run();
            }
        });
    }

    private CompletableFuture<String> renderingDisabled() {
        boolean previous = Configs.Visuals.ENABLE_RENDERING.getBooleanValue();
        Runnable restore = () -> Configs.Visuals.ENABLE_RENDERING.setBooleanValue(previous);
        cleanups.add(restore);
        Configs.Visuals.ENABLE_RENDERING.setBooleanValue(false);

        CompletableFuture<Outcome> paste = startPaste("b");
        String[] observed = new String[1];
        return delaySeconds(5).thenCompose(ignored -> {
            observed[0] = paste.isDone()
                    ? "OBSERVATION: the paste finished with rendering disabled"
                    : "waiting with " + paster.activePendingChunks() + "/" + paster.activeTotalChunks()
                    + " chunks pending, reason: " + paster.activeBlockedReason();
            LOGGER.info("[suite] With rendering disabled: {}", observed[0]);
            cleanups.remove(restore);
            restore.run();
            return withTimeout(paste, PASTE_TIMEOUT_SECONDS, "did not finish after re-enabling rendering");
        }).thenCompose(o -> {
            if (!o.applied()) {
                throw new CheckFailed("aborted: " + o.report().abortReason());
            }
            return verify().thenApply(v -> observed[0] + "; after re-enabling: " + describe(o.report()) + "; " + v);
        });
    }

    private CompletableFuture<String> stableAfterWait() {
        return delaySeconds(30).thenCompose(ignored -> verify());
    }

    /** A paste far beyond the render distance must wait (never write or report success) until aborted. */
    private CompletableFuture<String> unloadedArea() {
        BlockPos home = paster.origin();
        ResourceKey<Level> dimension = paster.dimension();
        paster.setOrigin(home.offset(4096, 0, 0), dimension);

        CompletableFuture<Outcome> paste = startPaste("a");
        String[] observed = new String[1];
        // Longer than the 30 s stall warning, so the watchdog line shows up in the log.
        return delaySeconds(32).thenCompose(ignored -> {
            if (paste.isDone()) {
                return CompletableFuture.failedFuture(new CheckFailed("the distant paste did not wait"));
            }
            observed[0] = "was waiting with " + paster.activePendingChunks() + "/" + paster.activeTotalChunks()
                    + " chunks pending, reason: " + paster.activeBlockedReason();
            paster.abort();
            return withTimeout(paste, 5, "the abort notification did not arrive");
        }).thenApply(o -> {
            if (o.applied()) {
                throw new CheckFailed("reported as applied; " + observed[0]);
            }
            return observed[0] + "; aborted (" + o.report().abortReason() + ")";
        }).whenComplete((detail, error) -> {
            if (!paster.isPasting()) {
                paster.setOrigin(home, dimension);
            }
        });
    }

    // --- Helpers ---

    private CompletableFuture<String> pasteAndVerify(String name) {
        return withTimeout(startPaste(name), PASTE_TIMEOUT_SECONDS, "the paste of " + name + " did not finish")
                .thenCompose(o -> {
                    if (!o.applied()) {
                        throw new CheckFailed(name + " aborted: " + o.report().abortReason());
                    }
                    return verify().thenApply(v -> describe(o.report()) + "; " + v);
                });
    }

    private CompletableFuture<Outcome> startPaste(String name) {
        CompletableFuture<Outcome> future = new CompletableFuture<>();
        try {
            paster.paste(SyntheticStructures.byName(name), new LitematicaPaster.Listener() {
                @Override
                public void onApplied(LitematicaPaster.PasteReport report) {
                    future.complete(new Outcome(true, report));
                }

                @Override
                public void onAborted(LitematicaPaster.PasteReport report) {
                    future.complete(new Outcome(false, report));
                }
            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private CompletableFuture<String> verify() {
        return Verification.lastApplied(mc, paster).thenApply(r -> {
            if (r.mismatches() > 0) {
                throw new CheckFailed(r.mismatches() + " differences in " + r.checked() + " positions; "
                        + String.join(" | ", r.samples()));
            }
            return "area matches (" + r.checked() + " positions, verified in " + r.millis() + " ms)";
        });
    }

    private <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future, int seconds, String message) {
        return waitUntil(future::isDone, seconds * TICKS_PER_SECOND).thenCompose(done -> {
            if (done) {
                return future;
            }
            paster.abort();
            return CompletableFuture.failedFuture(new CheckFailed(message + " in " + seconds + " s"));
        });
    }

    private CompletableFuture<Boolean> delaySeconds(int seconds) {
        return waitUntil(() -> false, seconds * TICKS_PER_SECOND);
    }

    /** Completes with true when the condition holds, or false after the timeout. */
    private CompletableFuture<Boolean> waitUntil(BooleanSupplier condition, int timeoutTicks) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        waiters.add(new Waiter(condition, ticks + timeoutTicks, future));
        return future;
    }

    /** A one-block placement outside the reserved area; it is only displayed, never pasted. */
    private SchematicPlacement createForeignPlacement() {
        AreaSelection area = new AreaSelection();
        area.setName("foreign");
        area.setExplicitOrigin(BlockPos.ZERO);
        area.addSubRegionBox(new Box(BlockPos.ZERO, BlockPos.ZERO, "foreign"), false);
        LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(area, "MapArtisan test");
        if (schematic == null) {
            throw new IllegalStateException("Litematica could not create the foreign schematic");
        }
        schematic.getSubRegionContainer("foreign").set(0, 0, 0, Blocks.GLASS.defaultBlockState());
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, paster.origin().offset(-4, 0, -4),
                "MapArtisan test (foreign placement)", true, true);
        placement.setShouldBeSaved(false);
        return placement;
    }

    private static SchematicPlacementManager manager() {
        return DataManager.getSchematicPlacementManager();
    }

    private static String describe(LitematicaPaster.PasteReport r) {
        return r.totalMillis() + " ms (hologram " + r.waitMillis() + ", paste " + r.pasteMillis() + ", "
                + r.chunks() + " chunks, retries " + r.retries() + ")";
    }

    private void record(String status, String label, String detail, long millis) {
        String line = status + " " + label + " [" + millis + " ms]: " + detail;
        summary.add(line);
        log(line);
    }

    private void finish() {
        long seconds = (System.nanoTime() - suiteStart) / 1_000_000_000L;
        log("Done in " + seconds + " s: " + (steps.size() - failures) + " OK, " + failures + " failed.");
        LOGGER.info("[suite] Summary:");
        summary.forEach(line -> LOGGER.info("[suite]   {}", strip(line)));
        stop(null);
    }

    private void stop(String reason) {
        if (reason != null) {
            log("Stopped: " + reason);
            paster.abort();
        }
        running = false;
        waiters.clear();
        for (Runnable cleanup : new ArrayList<>(cleanups)) {
            cleanup.run();
        }
        cleanups.clear();
    }

    private void log(String text) {
        LOGGER.info("[suite] {}", strip(text));
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§7[suite]§r " + text));
        }
    }

    private static String strip(String text) {
        return text.replaceAll("§.", "");
    }
}
