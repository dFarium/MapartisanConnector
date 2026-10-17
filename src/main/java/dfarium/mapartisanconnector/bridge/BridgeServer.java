package dfarium.mapartisanconnector.bridge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Local WebSocket listener on 127.0.0.1 only, using the Netty that ships with Minecraft. */
public final class BridgeServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("MapartisanConnector");

    private final BridgeConfig config;
    private final ConnectorSession session;
    private EventLoopGroup group;
    private Channel channel;

    public BridgeServer(BridgeConfig config, ConnectorSession session) {
        this.config = config;
        this.session = session;
    }

    /** Binds synchronously; throws IllegalStateException when the port is unavailable. */
    public void start() {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "MapArtisan bridge");
            thread.setDaemon(true);
            return thread;
        };
        group = new MultiThreadIoEventLoopGroup(1, threads, NioIoHandler.newFactory());
        WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath(HandshakeGuard.PATH)
                .maxFramePayloadLength(Limits.MAX_MESSAGE_BYTES)
                .allowExtensions(false)
                .handshakeTimeoutMillis(10_000)
                .build();
        try {
            channel = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(16 * 1024))
                                    .addLast(new Guard())
                                    .addLast(new WebSocketServerProtocolHandler(wsConfig))
                                    .addLast(new WebSocketFrameAggregator(Limits.MAX_MESSAGE_BYTES))
                                    .addLast(new BridgeConnection(session));
                        }
                    })
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port()))
                    .syncUninterruptibly()
                    .channel();
        } catch (Exception e) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            group = null;
            throw new IllegalStateException("Could not listen on 127.0.0.1:" + config.port() + ": " + e.getMessage(), e);
        }
        LOGGER.info("MapArtisan bridge listening on {}", channel.localAddress());
    }

    public void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            // Closes accepted connections too.
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS);
            group = null;
        }
    }

    public int port() {
        return config.port();
    }

    public String url() {
        return "ws://127.0.0.1:" + config.port() + HandshakeGuard.PATH;
    }

    /** Refuses the upgrade request when Host, Origin or the path are wrong; frames pass through. */
    private final class Guard extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof FullHttpRequest request)) {
                ctx.fireChannelRead(msg);
                return;
            }
            String path = new QueryStringDecoder(request.uri()).path();
            String reason = HandshakeGuard.reject(path, request.headers().get(HttpHeaderNames.HOST),
                    request.headers().get(HttpHeaderNames.ORIGIN), config.port(), config.allowedOrigins());
            if (reason == null) {
                ctx.fireChannelRead(msg);
                return;
            }
            ReferenceCountUtil.release(request);
            LOGGER.warn("Refused bridge connection from {}: {}", ctx.channel().remoteAddress(), reason);
            HttpResponseStatus status = HandshakeGuard.PATH.equals(path) ? HttpResponseStatus.FORBIDDEN : HttpResponseStatus.NOT_FOUND;
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                    Unpooled.copiedBuffer(reason, CharsetUtil.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            // The socket is closed after the response; tell the client not to reuse it.
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
