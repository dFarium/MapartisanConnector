package dfarium.mapartisanconnector.bridge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class HandshakeGuardTest {
    private static final List<String> ORIGINS = BridgeConfig.DEFAULT.allowedOrigins();
    private static final String PATH = HandshakeGuard.PATH;

    @Test
    void acceptsLoopbackHostsAndConfiguredOrigins() {
        assertNull(HandshakeGuard.reject(PATH, "127.0.0.1:25590", "http://localhost:5173", 25590, ORIGINS));
        assertNull(HandshakeGuard.reject(PATH, "localhost:25590", "http://127.0.0.1:4173", 25590, ORIGINS));
        assertNull(HandshakeGuard.reject(PATH, "LOCALHOST:25590", "HTTP://LOCALHOST:5173", 25590, ORIGINS));
    }

    @Test
    void rejectsRebindingHosts() {
        assertNotNull(HandshakeGuard.reject(PATH, "evil.example:25590", "http://localhost:5173", 25590, ORIGINS));
        assertNotNull(HandshakeGuard.reject(PATH, "127.0.0.1", "http://localhost:5173", 25590, ORIGINS));
        assertNotNull(HandshakeGuard.reject(PATH, "127.0.0.1:25591", "http://localhost:5173", 25590, ORIGINS));
        assertNotNull(HandshakeGuard.reject(PATH, null, "http://localhost:5173", 25590, ORIGINS));
    }

    @Test
    void rejectsOtherOrigins() {
        assertNotNull(HandshakeGuard.reject(PATH, "127.0.0.1:25590", "https://mapartisan.vercel.app", 25590, ORIGINS));
        assertNotNull(HandshakeGuard.reject(PATH, "127.0.0.1:25590", "http://localhost:5174", 25590, ORIGINS));
        assertNotNull(HandshakeGuard.reject(PATH, "127.0.0.1:25590", "null", 25590, ORIGINS));
        assertNotNull(HandshakeGuard.reject(PATH, "127.0.0.1:25590", null, 25590, ORIGINS));
    }

    @Test
    void rejectsOtherPaths() {
        assertNotNull(HandshakeGuard.reject("/", "127.0.0.1:25590", "http://localhost:5173", 25590, ORIGINS));
        assertNotNull(HandshakeGuard.reject("/mapartisan/v2", "127.0.0.1:25590", "http://localhost:5173", 25590, ORIGINS));
    }
}
