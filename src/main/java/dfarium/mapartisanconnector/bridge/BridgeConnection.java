package dfarium.mapartisanconnector.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dfarium.mapartisanconnector.structure.BuildStructure;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One WebSocket client. Runs on its Netty event loop: it only parses, assembles and validates
 * messages; everything that touches the game goes through ConnectorSession on the client thread.
 */
public final class BridgeConnection extends SimpleChannelInboundHandler<WebSocketFrame> {
    public static final int PROTOCOL_VERSION = 1;
    private static final Logger LOGGER = LoggerFactory.getLogger("MapartisanConnector");

    private final ConnectorSession session;
    private Channel channel;
    private long upgradedAt;
    private boolean authenticated;
    // Set on the client thread when sessionReady is sent.
    private volatile String openedSessionId;
    private SnapshotTransfer transfer;
    private ScheduledFuture<?> watchdog;

    public BridgeConnection(ConnectorSession session) {
        this.session = session;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            channel = ctx.channel();
            upgradedAt = System.nanoTime();
            watchdog = ctx.executor().scheduleAtFixedRate(this::checkTimeouts, 1, 1, TimeUnit.SECONDS);
            LOGGER.info("Bridge client connected from {}", channel.remoteAddress());
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (watchdog != null) {
            watchdog.cancel(false);
        }
        if (authenticated) {
            session.release(this);
            LOGGER.info("Bridge client disconnected");
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOGGER.warn("Bridge connection error: {}", cause.toString());
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame text) {
            onText(text.text());
        } else if (frame instanceof BinaryWebSocketFrame binary) {
            onBinary(binary);
        }
    }

    /** Thread-safe. */
    public void send(JsonObject message) {
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(message.toString()));
        }
    }

    /** Thread-safe. Sends a close frame with the reason, then closes the socket. */
    public void close(int status, String reason) {
        Channel ch = channel;
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new CloseWebSocketFrame(status, reason)).addListener(ChannelFutureListener.CLOSE);
        }
    }

    void markSessionOpened(String sessionId) {
        openedSessionId = sessionId;
    }

    static JsonObject message(String type) {
        JsonObject json = new JsonObject();
        json.addProperty("protocolVersion", PROTOCOL_VERSION);
        json.addProperty("type", type);
        return json;
    }

    static JsonObject rejected(String code, String text, Long revision) {
        JsonObject json = message("rejected");
        if (revision != null) {
            json.addProperty("revision", revision);
        }
        json.addProperty("code", code);
        json.addProperty("message", text);
        return json;
    }

    private void onText(String text) {
        JsonObject json;
        String type;
        try {
            json = JsonParser.parseString(text).getAsJsonObject();
            type = json.get("type").getAsString();
            if (json.get("protocolVersion").getAsInt() != PROTOCOL_VERSION) {
                fatal("PROTOCOL_MISMATCH", "the bridge speaks protocol " + PROTOCOL_VERSION);
                return;
            }
        } catch (JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException e) {
            fatal("BAD_MESSAGE", "invalid JSON message");
            return;
        }

        if (!authenticated) {
            if (!type.equals("hello")) {
                fatal("UNAUTHORIZED", "the first message must be hello");
                return;
            }
            String token = json.has("token") && json.get("token").isJsonPrimitive() ? json.get("token").getAsString() : "";
            String code = session.authenticate(this, token);
            if (code != null) {
                fatal(code, code.equals("BUSY") ? "another tab already has write control" : "invalid token or no active session");
                return;
            }
            authenticated = true;
            send(session.welcome());
            return;
        }

        try {
            switch (type) {
                case "openSession" -> session.openSession(this);
                case "snapshotBegin" -> onSnapshotBegin(json);
                case "snapshotEnd" -> onSnapshotEnd(json);
                case "ping" -> {
                    JsonObject pong = message("pong");
                    if (json.has("t")) {
                        pong.add("t", json.get("t"));
                    }
                    send(pong);
                }
                case "closeSession" -> close(1000, "session closed");
                default -> send(rejected("BAD_MESSAGE", "unknown message type: " + type, null));
            }
        } catch (ProtocolException e) {
            send(rejected(e.code(), e.getMessage(), revisionOf(json)));
        } catch (IllegalStateException | NullPointerException | UnsupportedOperationException | NumberFormatException e) {
            send(rejected("BAD_MESSAGE", "invalid fields in " + type, revisionOf(json)));
        }
    }

    private void onSnapshotBegin(JsonObject json) throws ProtocolException {
        requireSession(json);
        if (transfer != null) {
            throw new ProtocolException("BAD_MESSAGE", "a transfer is already in progress (revision " + transfer.begin().revision() + ")");
        }
        JsonObject size = json.getAsJsonObject("size");
        JsonArray paletteJson = json.getAsJsonArray("palette");
        if (paletteJson.size() > Limits.MAX_PALETTE) {
            throw new ProtocolException("LIMIT_EXCEEDED", "the palette exceeds " + Limits.MAX_PALETTE + " states");
        }
        List<String> palette = new ArrayList<>(paletteJson.size());
        for (JsonElement state : paletteJson) {
            palette.add(state.getAsString());
        }
        transfer = new SnapshotTransfer(new SnapshotTransfer.Begin(
                json.get("revision").getAsLong(),
                json.get("contentHash").getAsString(),
                json.get("count").getAsInt(),
                json.get("parts").getAsInt(),
                List.copyOf(palette),
                size.get("x").getAsInt(),
                size.get("y").getAsInt(),
                size.get("z").getAsInt()));
    }

    private void onBinary(BinaryWebSocketFrame frame) {
        SnapshotTransfer current = transfer;
        if (current == null) {
            send(rejected("BAD_MESSAGE", "binary part without snapshotBegin", null));
            return;
        }
        try {
            current.accept(frame.content().nioBuffer());
        } catch (ProtocolException e) {
            transfer = null;
            send(rejected(e.code(), e.getMessage(), current.begin().revision()));
        }
    }

    private void onSnapshotEnd(JsonObject json) throws ProtocolException {
        requireSession(json);
        SnapshotTransfer current = transfer;
        long revision = json.get("revision").getAsLong();
        if (current == null || current.begin().revision() != revision) {
            throw new ProtocolException("BAD_MESSAGE", "snapshotEnd without a transfer of revision " + revision);
        }
        transfer = null;
        long ended = System.nanoTime();
        SnapshotTransfer.Data data = current.finish();
        BuildStructure structure = SnapshotStructure.build(data, "revision " + revision);
        long validated = System.nanoTime();
        session.submit(this, data, structure, (ended - current.startedNanos()) / 1_000_000L, (validated - ended) / 1_000_000L);
    }

    private void requireSession(JsonObject json) throws ProtocolException {
        String opened = openedSessionId;
        if (opened == null) {
            throw new ProtocolException("NO_SESSION", "openSession is missing");
        }
        if (!json.has("sessionId") || !opened.equals(json.get("sessionId").getAsString())) {
            throw new ProtocolException("NO_SESSION", "sessionId does not match the open session");
        }
    }

    private void checkTimeouts() {
        long now = System.nanoTime();
        if (!authenticated && now - upgradedAt > Limits.HELLO_TIMEOUT_NANOS) {
            fatal("UNAUTHORIZED", "no hello within 10 s");
            return;
        }
        SnapshotTransfer current = transfer;
        if (current != null && now - current.lastProgressNanos() > Limits.TRANSFER_TIMEOUT_NANOS) {
            transfer = null;
            send(rejected("TRANSFER_TIMEOUT", "no parts received for 10 s", current.begin().revision()));
        }
    }

    private void fatal(String code, String text) {
        send(rejected(code, text, null));
        close(1008, code);
    }

    private static Long revisionOf(JsonObject json) {
        try {
            return json.has("revision") ? json.get("revision").getAsLong() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
