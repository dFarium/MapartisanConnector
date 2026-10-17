package dfarium.mapartisanconnector.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dfarium.mapartisanconnector.litematica.LitematicaPaster;
import dfarium.mapartisanconnector.structure.BuildStructure;
import dfarium.mapartisanconnector.verify.Verification;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The in-game session started with /mapartisan start: origin, token, listener and the revisions
 * received from MapArtisan. State that touches the game lives on the client thread; the network
 * thread only reads the immutable Active record and the writer reference.
 */
public final class ConnectorSession {
    private static final Logger LOGGER = LoggerFactory.getLogger("MapartisanConnector");
    private static final long PROGRESS_INTERVAL_NANOS = 250_000_000L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Active(String sessionId, byte[] token, BridgeServer server, BlockPos origin,
                          ResourceKey<Level> dimension, String world) {
    }

    /** A revision from `accepted` until `applied` or `failed`. */
    private static final class Pending {
        final BridgeConnection connection;
        final long revision;
        final String contentHash;
        final long receiveMs;
        final long validateMs;
        final long acceptedAt = System.nanoTime();
        String phase = "scheduled";
        String lastProgress = "";
        long lastProgressAt;

        Pending(BridgeConnection connection, long revision, String contentHash, long receiveMs, long validateMs) {
            this.connection = connection;
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
    private long lastRevision;
    private long appliedRevision;
    private String appliedHash;
    private Pending pending;

    public ConnectorSession(LitematicaPaster paster) {
        this.paster = paster;
    }

    // ---- Commands (client thread) ----

    /** Starts listening with a new token and fixes the origin. Returns the chat message to show. */
    public Component start(BlockPos origin) {
        if (active != null) {
            throw new IllegalStateException("A connector session is already running; use /mapartisan stop first");
        }
        if (!mc.hasSingleplayerServer() || mc.level == null || mc.getSingleplayerServer() == null) {
            throw new IllegalStateException("Only works in a singleplayer world");
        }
        BridgeConfig config = BridgeConfig.load(FabricLoader.getInstance().getConfigDir().resolve("mapartisan_connector.json"));
        // Throws while a test paste is running; the listener has not started yet.
        paster.setOrigin(origin, mc.level.dimension());

        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        BridgeServer server = new BridgeServer(config, this);
        server.start();

        String world = mc.getSingleplayerServer().getWorldData().getLevelName();
        active = new Active(UUID.randomUUID().toString(), token.getBytes(StandardCharsets.UTF_8), server,
                origin.immutable(), mc.level.dimension(), world);
        lastRevision = 0;
        appliedRevision = 0;
        appliedHash = null;
        pending = null;
        LOGGER.info("MapArtisan session started at {} in {} ({}), token ending in {}",
                origin, mc.level.dimension().identifier(), world, token.substring(token.length() - 4));

        String command = "await mapartisan.connector.connect(\"" + token + "\")";
        Component copy = Component.literal("[copy command]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.GREEN).withUnderlined(true)
                .withClickEvent(new ClickEvent.CopyToClipboard(command))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Paste it into the DevTools console of MapArtisan"))));
        // Only the visible text reaches the log: the full token stays in the click event.
        return Component.literal("Connector listening on " + server.url() + ". Origin " + origin.toShortString()
                        + ", noobline at z=" + origin.getZ() + ". Token …" + token.substring(token.length() - 4) + " ")
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
            return "No connector session. " + paster.status();
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

    /** Client tick: ends the session with the world and reports paste progress. */
    public void tick(Minecraft client) {
        if (active == null) {
            return;
        }
        if (client.level == null) {
            stop("world closed");
            return;
        }
        Pending p = pending;
        if (p == null || !p.phase.equals("pasting") && !p.phase.equals("waiting") && !p.phase.equals("scheduled")) {
            return;
        }
        int pendingChunks = paster.activePendingChunks();
        int totalChunks = paster.activeTotalChunks();
        String blocked = paster.activeBlockedReason();
        p.phase = pendingChunks < 0 ? "scheduled" : blocked != null ? "waiting" : "pasting";
        String key = p.phase + pendingChunks + "/" + totalChunks + blocked;
        long now = System.nanoTime();
        if (!key.equals(p.lastProgress) && now - p.lastProgressAt >= PROGRESS_INTERVAL_NANOS) {
            p.lastProgress = key;
            p.lastProgressAt = now;
            JsonObject progress = message("progress", p.revision);
            progress.addProperty("phase", p.phase);
            progress.addProperty("pendingChunks", pendingChunks);
            progress.addProperty("totalChunks", totalChunks);
            if (blocked != null) {
                progress.addProperty("blockedReason", blocked);
            }
            p.connection.send(progress);
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
            JsonObject height = new JsonObject();
            height.addProperty("min", mc.level.getMinY());
            height.addProperty("max", mc.level.getMaxY());
            ready.add("buildHeight", height);
            ready.addProperty("lastRevision", lastRevision);
            if (appliedRevision > 0) {
                JsonObject applied = new JsonObject();
                applied.addProperty("revision", appliedRevision);
                applied.addProperty("contentHash", appliedHash);
                ready.add("lastApplied", applied);
            } else {
                ready.add("lastApplied", null);
            }
            connection.markSessionOpened(current.sessionId());
            connection.send(ready);
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
        if (revision == appliedRevision) {
            if (hash.equals(appliedHash)) {
                // A repeated revision is confirmed without pasting again (spec section 8).
                JsonObject applied = message("applied", revision);
                applied.addProperty("contentHash", hash);
                applied.addProperty("repeated", true);
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

        Pending p = new Pending(connection, revision, hash, receiveMs, validateMs);
        try {
            paster.paste(structure, new LitematicaPaster.Listener() {
                @Override
                public void onApplied(LitematicaPaster.PasteReport report) {
                    verify(p, report);
                }

                @Override
                public void onAborted(LitematicaPaster.PasteReport report) {
                    fail(p, report.abortReason().equals(LitematicaPaster.WORLD_CLOSED) ? "WORLD_CLOSED" : "ABORTED",
                            report.abortReason(), null);
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
                fail(p, "VERIFY_FAILED", error.getMessage(), null);
                return;
            }
            if (result.mismatches() > 0) {
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
            p.connection.send(applied);
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
        if (samples != null) {
            failed.add("samples", samples);
        }
        p.connection.send(failed);
        LOGGER.warn("Revision {} failed: {} {}", p.revision, code, text);
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
                .orElse("desconocida");
    }
}
