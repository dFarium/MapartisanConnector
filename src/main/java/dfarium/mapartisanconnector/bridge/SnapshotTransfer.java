package dfarium.mapartisanconnector.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Assembles one snapshot from snapshotBegin, its binary parts and snapshotEnd (spec section 8).
 *
 * Binary part: "MAB1", revision (u32), part index (u16), reserved (u16), then one 8-byte record
 * per block: x, y, z and palette index as u16, all little-endian. Nothing here touches the game,
 * so it runs on the network thread.
 */
public final class SnapshotTransfer {
    public static final int HEADER_BYTES = 12;
    public static final int RECORD_BYTES = 8;
    private static final byte[] MAGIC = "MAB1".getBytes(StandardCharsets.US_ASCII);

    public record Begin(long revision, String contentHash, int count, int parts, List<String> palette,
                        int sizeX, int sizeY, int sizeZ) {
    }

    /** A complete transfer whose positions, indices and hash were checked against Begin. */
    public record Data(Begin begin, int[] xs, int[] ys, int[] zs, int[] indices) {
    }

    private final Begin begin;
    private final byte[][] parts;
    private int receivedParts;
    private int receivedRecords;
    private long receivedBytes;
    private final long startedNanos = System.nanoTime();
    private long lastProgressNanos = startedNanos;

    public SnapshotTransfer(Begin begin) throws ProtocolException {
        if (begin.revision() < 1 || begin.revision() > 0xffffffffL) {
            throw new ProtocolException("BAD_MESSAGE", "revision out of range: " + begin.revision());
        }
        if (begin.contentHash() == null || !begin.contentHash().matches("[0-9a-f]{16}")) {
            throw new ProtocolException("BAD_MESSAGE", "contentHash must be 16 hex digits");
        }
        if (begin.count() < 1 || begin.count() > Limits.MAX_POSITIONS) {
            throw new ProtocolException("LIMIT_EXCEEDED", "count outside 1.." + Limits.MAX_POSITIONS + ": " + begin.count());
        }
        if (begin.palette().isEmpty() || begin.palette().size() > Limits.MAX_PALETTE) {
            throw new ProtocolException("LIMIT_EXCEEDED", "the palette must have 1.." + Limits.MAX_PALETTE + " states");
        }
        for (String state : begin.palette()) {
            if (state == null || state.isEmpty() || state.length() > Limits.MAX_STATE_LENGTH) {
                throw new ProtocolException("BAD_MESSAGE", "empty or too long block state in the palette");
            }
        }
        if (begin.sizeX() < 1 || begin.sizeX() > Limits.MAX_SIZE_XZ || begin.sizeZ() < 1 || begin.sizeZ() > Limits.MAX_SIZE_XZ
                || begin.sizeY() < 1 || begin.sizeY() > Limits.MAX_SIZE_Y) {
            throw new ProtocolException("LIMIT_EXCEEDED", "size outside the limits: "
                    + begin.sizeX() + "×" + begin.sizeY() + "×" + begin.sizeZ());
        }
        long volume = (long) begin.sizeX() * begin.sizeY() * begin.sizeZ();
        if (volume > Limits.MAX_VOLUME) {
            throw new ProtocolException("LIMIT_EXCEEDED", "volume " + volume + " > " + Limits.MAX_VOLUME);
        }
        long bytes = (long) begin.count() * RECORD_BYTES + (long) begin.parts() * HEADER_BYTES;
        int minParts = (int) Math.ceil((double) begin.count() * RECORD_BYTES / (Limits.MAX_MESSAGE_BYTES - HEADER_BYTES));
        if (begin.parts() < minParts || begin.parts() > begin.count() || bytes > Limits.MAX_TRANSFER_BYTES) {
            throw new ProtocolException("LIMIT_EXCEEDED", "parts must be between " + minParts + " and " + begin.count()
                    + " for " + begin.count() + " blocks: " + begin.parts());
        }
        this.begin = begin;
        this.parts = new byte[begin.parts()][];
    }

    public Begin begin() {
        return begin;
    }

    public long lastProgressNanos() {
        return lastProgressNanos;
    }

    public long startedNanos() {
        return startedNanos;
    }

    /** Stores one binary part. Parts may arrive in any order, each exactly once. */
    public void accept(ByteBuffer frame) throws ProtocolException {
        ByteBuffer buf = frame.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int length = buf.remaining();
        if (length < HEADER_BYTES + RECORD_BYTES || (length - HEADER_BYTES) % RECORD_BYTES != 0) {
            throw new ProtocolException("BAD_MESSAGE", "malformed binary part of " + length + " bytes");
        }
        for (byte b : MAGIC) {
            if (buf.get() != b) {
                throw new ProtocolException("BAD_MESSAGE", "the binary part does not start with MAB1");
            }
        }
        long revision = buf.getInt() & 0xffffffffL;
        int part = buf.getShort() & 0xffff;
        buf.getShort();
        if (revision != begin.revision()) {
            throw new ProtocolException("BAD_MESSAGE", "part of revision " + revision + " during revision " + begin.revision());
        }
        if (part >= parts.length) {
            throw new ProtocolException("BAD_MESSAGE", "part " + part + " outside 0.." + (parts.length - 1));
        }
        if (parts[part] != null) {
            throw new ProtocolException("BAD_MESSAGE", "part " + part + " received twice");
        }
        int records = (length - HEADER_BYTES) / RECORD_BYTES;
        if ((long) receivedRecords + records > begin.count()) {
            throw new ProtocolException("BAD_MESSAGE", "more blocks than the " + begin.count() + " declared");
        }
        byte[] payload = new byte[length - HEADER_BYTES];
        buf.get(payload);
        parts[part] = payload;
        receivedParts++;
        receivedRecords += records;
        receivedBytes += length;
        lastProgressNanos = System.nanoTime();
    }

    /** Validates the complete transfer at snapshotEnd. */
    public Data finish() throws ProtocolException {
        if (receivedParts != parts.length || receivedRecords != begin.count()) {
            throw new ProtocolException("TRANSFER_INCOMPLETE", "received " + receivedParts + "/" + parts.length
                    + " parts and " + receivedRecords + "/" + begin.count() + " blocks");
        }
        int count = begin.count();
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] zs = new int[count];
        int[] indices = new int[count];
        int paletteSize = begin.palette().size();
        int maxX = 0, maxY = 0, maxZ = 0;
        int i = 0;
        for (byte[] part : parts) {
            ByteBuffer buf = ByteBuffer.wrap(part).order(ByteOrder.LITTLE_ENDIAN);
            while (buf.hasRemaining()) {
                xs[i] = buf.getShort() & 0xffff;
                ys[i] = buf.getShort() & 0xffff;
                zs[i] = buf.getShort() & 0xffff;
                indices[i] = buf.getShort() & 0xffff;
                if (indices[i] >= paletteSize) {
                    throw new ProtocolException("INVALID_STRUCTURE", "palette index " + indices[i] + " at block " + i);
                }
                maxX = Math.max(maxX, xs[i]);
                maxY = Math.max(maxY, ys[i]);
                maxZ = Math.max(maxZ, zs[i]);
                i++;
            }
        }
        if (maxX + 1 != begin.sizeX() || maxY + 1 != begin.sizeY() || maxZ + 1 != begin.sizeZ()) {
            throw new ProtocolException("INVALID_STRUCTURE", "the blocks span " + (maxX + 1) + "×" + (maxY + 1) + "×"
                    + (maxZ + 1) + ", not the declared size " + begin.sizeX() + "×" + begin.sizeY() + "×" + begin.sizeZ());
        }
        String hash = ContentHash.of(begin.palette(), xs, ys, zs, indices);
        if (!hash.equals(begin.contentHash())) {
            throw new ProtocolException("HASH_MISMATCH", "received hash " + hash + ", declared " + begin.contentHash());
        }
        return new Data(begin, xs, ys, zs, indices);
    }

    public long receivedBytes() {
        return receivedBytes;
    }
}
