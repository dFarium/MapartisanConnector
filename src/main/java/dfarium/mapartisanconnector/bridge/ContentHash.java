package dfarium.mapartisanconnector.bridge;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * FNV-1a 64-bit content hash of a snapshot, identical to MapArtisan's
 * src/utils/export/snapshot.ts: the palette joined by newlines plus a NUL byte,
 * then x, y, z and palette index of every block as little-endian int32.
 */
public final class ContentHash {
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private long hash = OFFSET_BASIS;

    private ContentHash() {
    }

    public static String of(List<String> palette, int[] xs, int[] ys, int[] zs, int[] indices) {
        ContentHash h = new ContentHash();
        h.update(String.join("\n", palette).getBytes(StandardCharsets.UTF_8));
        h.update((byte) 0);
        for (int i = 0; i < xs.length; i++) {
            h.updateInt(xs[i]);
            h.updateInt(ys[i]);
            h.updateInt(zs[i]);
            h.updateInt(indices[i]);
        }
        return h.hex();
    }

    public static String of(byte[] bytes) {
        ContentHash h = new ContentHash();
        h.update(bytes);
        return h.hex();
    }

    private void update(byte[] bytes) {
        for (byte b : bytes) {
            update(b);
        }
    }

    private void update(byte b) {
        hash ^= b & 0xff;
        hash *= PRIME;
    }

    private void updateInt(int value) {
        update((byte) value);
        update((byte) (value >>> 8));
        update((byte) (value >>> 16));
        update((byte) (value >>> 24));
    }

    private String hex() {
        return String.format("%016x", hash);
    }
}
