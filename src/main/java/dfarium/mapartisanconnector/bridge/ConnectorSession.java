package dfarium.mapartisanconnector.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dfarium.mapartisanconnector.litematica.LitematicaPaster;
import dfarium.mapartisanconnector.structure.Bounds;
import dfarium.mapartisanconnector.structure.BuildStructure;
import dfarium.mapartisanconnector.verify.Verification;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The in-game session started with /mapartisan start: reserved area, token, listener and the
 * revisions received from MapArtisan. State that touches the game lives on the client thread;
 * the network thread only reads the immutable Active record and the writer reference.
 *
 * Results go to whichever tab holds the session when they are ready, so a tab that reconnects
 * during a paste still gets its outcome (spec section 9).
 */
public final class ConnectorSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("MapartisanConnector");
    private static final long PROGRESS_INTERVAL_NANOS = 250_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Active(String sessionId, byte[] token, BridgeServer server, BlockPos origin,
                          ResourceKey<Level> dimension, String world, Bounds area) {
    }

    /** A revision from `accepted` until `applied` or `failed`. */
    private static final class Pending {
        final long revision;
        final String contentHash;
        final long receiveMs;
        final long validateMs;
        final long acceptedAt = System.nanoTime();
        String phase = "scheduled";
        int pendingChunks = -1;
        int totalChunks = -1;
        String blockedReason;
        String lastProgress = "";
        long lastProgressAt;

        Pending(long revision, String contentHash, long receiveMs, long validateMs) {
            this.revision = revision;
            this.contentHash = contentHash;
            this.receiveMs = receiveMs;
            this.validateMs = validateMs;
        }
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final LitematicaPaster paster;
    private volatile Active active;
    private final AtomicReference<BridgeConnection> writer = new AtomicReference<>();

    // Client thread only.
    private boolean starting;
    private long lastRevision;
    private long appliedRevision;
    private String appliedHash;
    private Pending pending;

    public ConnectorSession(LitematicaPaster paster) {
        this.paster = paster;
    }

    // ---- Commands (client thread) ----

    /**
     * Checks that the reserved area is empty on the server thread, then starts listening with a
     * new token. With force, an occupied area is accepted and the first revision replaces it.
     * Throws IllegalStateException when the start is refused right away.
     */
    public void start(BlockPos origin, boolean force, Consumer<Component> feedback, Consumer<String> error) {
        if (active != null || starting) {
            throw new IllegalStateException("A connector session is already running; use /mapartisan stop first");
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (!mc.hasSingleplayerServer() || mc.level == null || server == null) {
            throw new IllegalStateException("Only works in a singleplayer world");
        }
        if (paster.isPasting()) {
            throw new IllegalStateException("A test paste is running; wait for it or use /mapartisan test abort");
        }
        if (mc.level.isOutsideBuildHeight(origin.getY())) {
            throw new IllegalStateException("The origin Y " + origin.getY() + " is outside the world ("
                    + mc.level.getMinY() + ".." + mc.level.getMaxY() + ")");
        }
        BridgeConfig config = BridgeConfig.load(FabricLoader.getInstance().getConfigDir().resolve("mapartisan_connector.json"));
        ResourceKey<Level> dimension = mc.level.dimension();
        Bounds area = ReservedArea.bounds(origin.getY(), mc.level.getMaxY());
        BlockPos fixed = origin.immutable();

        starting = true;
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            ReservedArea.Scan scan = level == null ? null : ReservedArea.scan(level, fixed, area);
            mc.execute(() -> {
                starting = false;
                try {
                    feedback.accept(finishStart(config, fixed, dimension, area, scan, force));
                } catch (IllegalStateException e) {
                    error.accept(e.getMessage());
                }
            });
        });
    }

    private Component finishStart(BridgeConfig config, BlockPos origin, ResourceKey<Level> dimension, Bounds area,
                                  ReservedArea.Scan scan, boolean force) {
        if (scan == null || mc.level == null || !mc.level.dimension().equals(dimension)) {
            throw new IllegalStateException("The world changed while checking the reserved area");
        }
        LOGGER.info("Reserved area {} at {}: {} blocks, {} chunks not loaded, scanned in {} ms",
                area, origin, scan.occupied(), scan.unloadedChunks(), scan.millis());
        if (scan.unloadedChunks() > 0) {
            throw new IllegalStateException(scan.unloadedChunks() + " chunks of the reserved area are not loaded; "
                    + "move closer to it and try again");
        }
        String areaText = ReservedArea.WIDTH + "×" + ReservedArea.DEPTH + " from " + origin.toShortString()
                + " up to y=" + (origin.getY() + area.maxY());
        if (scan.occupied() > 0 && !force) {
            throw new IllegalStateException("The reserved area (" + areaText + ") is not empty: " + scan.occupied()
                    + " blocks, the first at " + scan.first().toShortString()
                    + ". Clear it or use /mapartisan start ... force to replace them");
        }
        if (paster.isPasting()) {
            throw new IllegalStateException("A test paste started meanwhile; wait for it and try again");
        }
        // With force, the first revision also covers whatever is in the area now.
        paster.setOrigin(origin, dimension, scan.occupied() > 0 ? scan.occupiedBounds() : null);

        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        BridgeServer server = new BridgeServer(config, this);
        server.start();

        String world = mc.getSingleplayerServer().getWorldData().getLevelName();
        active = new Active(UUID.randomUUID().toString(), token.getBytes(StandardCharsets.UTF_8), server,
                origin, dimension, world, area);
        lastRevision = 0;
        appliedRevision = 0;
        appliedHash = null;
        pending = null;
        LOGGER.info("MapArtisan session started at {} in {} ({}), token ending in {}",
                origin, dimension.identifier(), world, token.substring(token.length() - 4));

        String command = "await mapartisan.connector.connect(\"" + token + "\")";
        Component copy = Component.literal("[copy command]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Paste it into the DevTools console of MapArtisan"))));
        String occupiedText = scan.occupied() == 0 ? "empty" : scan.occupied() + " blocks will be replaced by the first revision";
        // Only the visible text reaches the log: the full token stays in the click event.
        return Component.literal("Connector listening on " + server.url() + ". Reserved area " + areaText
                        + " (" + occupiedText + "), noobline at z=" + origin.getZ()
                        + ". Token …" + token.substring(token.length() - 4) + " ")
                .append(copy);
    }

    /** Closes the connection and the listener. Blocks already pasted stay in the world. */
    public boolean stop(String reason) {
        Active current = active;
        if (current == null) {
            return false;
        }
        active = null;
        if (pending != null && paster.isPasting()) {
            paster.abort();
        }
        BridgeConnection connection = writer.getAndSet(null);
        if (connection != null) {
            connection.close(1001, reason);
        }
        current.server().stop();
        LOGGER.info("MapArtisan session stopped: {}", reason);
        return true;
    }

    public String status() {
        Active current = active;
        if (current == null) {
            return (starting ? "Checking the reserved area. " : "No connector session. ") + paster.status();
        }
        StringBuilder sb = new StringBuilder("Session on ").append(current.server().url())
                .append(" | ").append(writer.get() == null ? "no tab connected" : "tab connected")
                .append(" | last revision ").append(lastRevision)
                .append(", applied ").append(appliedRevision == 0 ? "none" : appliedRevision);
        if (pending != null) {
            sb.append(" | revision ").append(pending.revision).append(": ").append(pending.phase);
        }
        return sb.append(" | ").append(paster.status()).toString();
    }

    /** Client tick: ends the session with the world or dimension, and reports paste progress. */
    public void tick(Minecraft client) {
        Active current = active;
        if (current == null) {
            return;
        }
        if (client.level == null) {
            stop("world closed");
            return;
        }
        if (!client.level.dimension().equals(current.dimension())) {
            // The paster has already reported a paste this interrupted (spec section 9).
            stop("dimension changed");
            return;
        }
        Pending p = pending;
        if (p == null || p.phase.equals("verifying")) {
            return;
        }
        p.pendingChunks = paster.activePendingChunks();
        p.totalChunks = paster.activeTotalChunks();
        p.blockedReason = paster.activeBlockedReason();
        // A paused game does not tick the integrated server: the paste waits for the game.
        p.phase = client.isPaused() ? "paused"
                : p.pendingChunks < 0 ? "scheduled" : p.blockedReason != null ? "waiting" : "pasting";
        String key = p.phase + p.pendingChunks + "/" + p.totalChunks + p.blockedReason;
        long now = System.nanoTime();
        if (!key.equals(p.lastProgress) && now - p.lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
            p.lastProgress = key;
            p.lastProgressAt = now;
            JsonObject progress = message("progress", p.revision);
            addProgress(progress, p);
            deliver(progress);
        }
    }

    // ---- Network thread ----

    /** Returns null when the token is valid and no other tab holds the session, otherwise the rejection code. */
    String authenticate(BridgeConnection connection, String token) {
        Active current = active;
        if (current == null || !MessageDigest.isEqual(current.token(), token.getBytes(StandardCharsets.UTF_8))) {
            LOGGER.warn("Bridge client sent an invalid token");
            return "UNAUTHORIZED";
        }
        return writer.compareAndSet(null, connection) ? null : "BUSY";
    }

    void release(BridgeConnection connection) {
        writer.compareAndSet(connection, null);
    }

    JsonObject welcome() {
        JsonObject welcome = BridgeConnection.message("welcome");
        welcome.addProperty("mod", modVersion("mapartisan_connector"));
        welcome.addProperty("minecraft", modVersion("minecraft"));
        welcome.addProperty("litematica", modVersion("litematica"));
        JsonObject limits = new JsonObject();
        limits.addProperty("maxMessageBytes", Limits.MAX_MESSAGE_BYTES);
        limits.addProperty("maxTransferBytes", Limits.MAX_TRANSFER_BYTES);
        limits.addProperty("maxPositions", Limits.MAX_POSITIONS);
        limits.addProperty("maxVolume", Limits.MAX_VOLUME);
        welcome.add("limits", limits);
        return welcome;
    }

    void openSession(BridgeConnection connection) {
        mc.execute(() -> {
            Active current = active;
            if (current == null || writer.get() != connection || mc.level == null) {
                connection.send(BridgeConnection.rejected("NO_SESSION", "the game session ended", null));
                return;
            }
            JsonObject ready = BridgeConnection.message("sessionReady");
            ready.addProperty("sessionId", current.sessionId());
            ready.addProperty("world", current.world());
            ready.addProperty("dimension", current.dimension().identifier().toString());
            JsonObject origin = new JsonObject();
            origin.addProperty("x", current.origin().getX());
            origin.addProperty("y", current.origin().getY());
            origin.addProperty("z", current.origin().getZ());
            ready.add("origin", origin);
            JsonObject area = new JsonObject();
            area.addProperty("x", current.area().sizeX());
            area.addProperty("y", current.area().sizeY());
            area.addProperty("z", current.area().sizeZ());
            ready.add("area", area);
            JsonObject height = new JsonObject();
            height.addProperty("min", mc.level.getMinY());
            height.addProperty("max", mc.level.getMaxY());
            ready.add("buildHeight", height);
            addState(ready);
            connection.markSessionOpened(current.sessionId());
            connection.send(ready);
        });
    }

    /** Answers `status`: what a reconnecting tab needs to continue (spec section 9). */
    void status(BridgeConnection connection) {
        mc.execute(() -> {
            Active current = active;
            if (current == null || writer.get() != connection) {
                connection.send(BridgeConnection.rejected("NO_SESSION", "the game session ended", null));
                return;
            }
            JsonObject status = BridgeConnection.message("status");
            status.addProperty("sessionId", current.sessionId());
            addState(status);
            connection.send(status);
        });
    }

    /** A validated snapshot; revision rules and the paste run on the client thread. */
    void submit(BridgeConnection connection, SnapshotTransfer.Data data, BuildStructure structure, long receiveMs, long validateMs) {
        mc.execute(() -> apply(connection, data, structure, receiveMs, validateMs));
    }

    // ---- Client thread ----

    private void apply(BridgeConnection connection, SnapshotTransfer.Data data, BuildStructure structure,
                       long receiveMs, long validateMs) {
        long revision = data.begin().revision();
        String hash = data.begin().contentHash();
        Active current = active;
        if (current == null || writer.get() != connection) {
            connection.send(BridgeConnection.rejected("NO_SESSION", "the game session ended", revision));
            return;
        }
        if (!current.origin().equals(paster.origin()) || !current.dimension().equals(paster.dimension())) {
            // /mapartisan test origin moved the paster; never paste outside the area sessionReady announced.
            connection.send(BridgeConnection.rejected("NOT_READY",
                    "the origin changed during the session; use /mapartisan stop and /mapartisan start", revision));
            return;
        }
        Bounds area = current.area();
        if (structure.sizeX() > area.sizeX() || structure.sizeY() > area.sizeY() || structure.sizeZ() > area.sizeZ()) {
            // Nothing outside the reserved area is ever written (acceptance criterion 7).
            connection.send(BridgeConnection.rejected("OUT_OF_AREA", "the structure is " + structure.sizeX() + "×"
                    + structure.sizeY() + "×" + structure.sizeZ() + " but the reserved area is " + area.sizeX() + "×"
                    + area.sizeY() + "×" + area.sizeZ(), revision));
            return;
        }
        if (revision == appliedRevision) {
            if (hash.equals(appliedHash)) {
                // A repeated revision is confirmed without pasting again (spec section 8).
                JsonObject applied = message("applied", revision);
                applied.addProperty("contentHash", hash);
                applied.addProperty("repeated", true);
                applied.addProperty("needsResync", paster.needsResync());
                connection.send(applied);
            } else {
                connection.send(BridgeConnection.rejected("REVISION_CONFLICT",
                        "revision " + revision + " was already applied with different content", revision));
            }
            return;
        }
        if (revision <= lastRevision) {
            connection.send(BridgeConnection.rejected("STALE_REVISION",
                    "revision " + revision + " is not after revision " + lastRevision, revision));
            return;
        }
        if (pending != null) {
            connection.send(BridgeConnection.rejected("BUSY",
                    "revision " + pending.revision + " is still being applied", revision));
            return;
        }

        Pending p = new Pending(revision, hash, receiveMs, validateMs);
        try {
            paster.paste(structure, new LitematicaPaster.Listener() {
                @Override
                public void onApplied(LitematicaPaster.PasteReport report) {
                    verify(p, report);
                }

                @Override
                public void onAborted(LitematicaPaster.PasteReport report) {
                    String reason = report.abortReason();
                    String code = reason.equals(LitematicaPaster.WORLD_CLOSED) ? "WORLD_CLOSED"
                            : reason.equals(LitematicaPaster.DIMENSION_CHANGED) ? "DIMENSION_CHANGED" : "ABORTED";
                    fail(p, code, reason, null);
                }
            });
        } catch (IllegalStateException e) {
            // Checked before anything is written: origin, world, game mode and build height.
            connection.send(BridgeConnection.rejected("NOT_READY", e.getMessage(), revision));
            return;
        }
        lastRevision = revision;
        pending = p;
        connection.send(message("accepted", revision));
    }

    /** `applied` is only sent when the area matches the snapshot block for block. */
    private void verify(Pending p, LitematicaPaster.PasteReport report) {
        p.phase = "verifying";
        Verification.lastApplied(mc, paster).whenComplete((result, error) -> {
            if (pending != p) {
                return;
            }
            if (error != null) {
                paster.markNeedsResync(report.volume());
                fail(p, "VERIFY_FAILED", error.getMessage(), null);
                return;
            }
            if (result.mismatches() > 0) {
                paster.markNeedsResync(report.volume());
                JsonArray samples = new JsonArray();
                result.samples().forEach(samples::add);
                fail(p, "VERIFY_MISMATCH", result.mismatches() + " of " + result.checked()
                        + " positions do not match the revision", samples);
                return;
            }
            pending = null;
            appliedRevision = p.revision;
            appliedHash = p.contentHash;

            JsonObject applied = message("applied", p.revision);
            applied.addProperty("contentHash", p.contentHash);
            applied.addProperty("repeated", false);
            applied.addProperty("needsResync", paster.needsResync());
            applied.addProperty("verifiedPositions", result.checked());
            JsonObject timings = new JsonObject();
            timings.addProperty("receiveMs", p.receiveMs);
            timings.addProperty("validateMs", p.validateMs);
            timings.addProperty("waitMs", report.waitMillis());
            timings.addProperty("pasteMs", report.pasteMillis());
            timings.addProperty("verifyMs", result.millis());
            timings.addProperty("totalMs", (System.nanoTime() - p.acceptedAt) / 1_000_000L);
            applied.add("timings", timings);
            applied.addProperty("chunks", report.chunks());
            deliver(applied);
            LOGGER.info("Revision {} applied and verified ({} positions)", p.revision, result.checked());
        });
    }

    private void fail(Pending p, String code, String text, JsonArray samples) {
        if (pending != p) {
            return;
        }
        pending = null;
        JsonObject failed = message("failed", p.revision);
        failed.addProperty("code", code);
        failed.addProperty("message", text);
        // Any part of the volume may have been written: the next revision must resynchronize it.
        failed.addProperty("partial", true);
        failed.addProperty("needsResync", paster.needsResync());
        if (samples != null) {
            failed.add("samples", samples);
        }
        deliver(failed);
        LOGGER.warn("Revision {} failed: {} {}", p.revision, code, text);
    }

    /** Sends to the tab holding the session now; with no tab, the outcome stays in the state. */
    private void deliver(JsonObject message) {
        BridgeConnection connection = writer.get();
        if (connection != null) {
            connection.send(message);
        }
    }

    /** Revisions, resync flag, pause and the revision in progress, for sessionReady and status. */
    private void addState(JsonObject json) {
        json.addProperty("lastRevision", lastRevision);
        if (appliedRevision > 0) {
            JsonObject applied = new JsonObject();
            applied.addProperty("revision", appliedRevision);
            applied.addProperty("contentHash", appliedHash);
            json.add("lastApplied", applied);
        } else {
            json.add("lastApplied", null);
        }
        json.addProperty("needsResync", paster.needsResync());
        json.addProperty("paused", mc.isPaused());
        if (pending != null) {
            JsonObject inProgress = new JsonObject();
            inProgress.addProperty("revision", pending.revision);
            inProgress.addProperty("contentHash", pending.contentHash);
            addProgress(inProgress, pending);
            json.add("pending", inProgress);
        } else {
            json.add("pending", null);
        }
    }

    private static void addProgress(JsonObject json, Pending p) {
        json.addProperty("phase", p.phase);
        json.addProperty("pendingChunks", p.pendingChunks);
        json.addProperty("totalChunks", p.totalChunks);
        if (p.blockedReason != null) {
            json.addProperty("blockedReason", p.blockedReason);
        }
    }

    private JsonObject message(String type, long revision) {
        JsonObject json = BridgeConnection.message(type);
        Active current = active;
        if (current != null) {
            json.addProperty("sessionId", current.sessionId());
        }
        json.addProperty("revision", revision);
        return json;
    }

    private static String modVersion(String id) {
        return FabricLoader.getInstance().getModContainer(id)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
