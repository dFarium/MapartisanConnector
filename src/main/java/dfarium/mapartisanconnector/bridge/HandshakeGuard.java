package dfarium.mapartisanconnector.bridge;

import java.util.List;
import java.util.Locale;

/**
 * Checks the HTTP upgrade request before the WebSocket handshake (spec sections 8 and 10.3).
 *
 * Host must name the loopback listener itself, which blocks DNS rebinding: a page on
 * another domain that resolves to 127.0.0.1 still sends its own name as Host.
 * Origin must be one of the configured local MapArtisan origins.
 */
public final class HandshakeGuard {
    public static final String PATH = "/mapartisan/v1";

    private HandshakeGuard() {
    }

    /** Returns null when the request may upgrade, or the reason it may not. */
    public static String reject(String path, String host, String origin, int port, List<String> allowedOrigins) {
        if (!PATH.equals(path)) {
            return "unknown path";
        }
        if (host == null) {
            return "missing Host header";
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (!h.equals("127.0.0.1:" + port) && !h.equals("localhost:" + port)) {
            return "Host not allowed: " + host;
        }
        if (origin == null) {
            return "missing Origin header";
        }
        String o = origin.trim();
        for (String allowed : allowedOrigins) {
            if (allowed.equalsIgnoreCase(o)) {
                return null;
            }
        }
        return "Origin not allowed: " + origin;
    }
}
