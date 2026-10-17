package dfarium.mapartisanconnector.bridge;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotTransferTest {
    private static final List<String> PALETTE = List.of("minecraft:stone", "minecraft:oak_leaves[persistent=true]");
    // The shared hash vector: blocks (0,0,0)=0, (1,2,3)=1, (127,300,128)=0.
    private static final int[][] BLOCKS = {{0, 0, 0, 0}, {1, 2, 3, 1}, {127, 300, 128, 0}};
    private static final String HASH = "4647d62b64c2a609";

    private static SnapshotTransfer.Begin begin(int parts) {
        return new SnapshotTransfer.Begin(7, HASH, BLOCKS.length, parts, PALETTE, 128, 301, 129);
    }

    private static ByteBuffer part(long revision, int index, int[]... blocks) {
        ByteBuffer buf = ByteBuffer.allocate(SnapshotTransfer.HEADER_BYTES + blocks.length * SnapshotTransfer.RECORD_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buf.put("MAB1".getBytes(StandardCharsets.US_ASCII)).putInt((int) revision).putShort((short) index).putShort((short) 0);
        for (int[] b : blocks) {
            buf.putShort((short) b[0]).putShort((short) b[1]).putShort((short) b[2]).putShort((short) b[3]);
        }
        return buf.flip();
    }

    private static String code(ThrowingRunnable action) {
        return assertThrows(ProtocolException.class, action::run).code();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void assemblesPartsInAnyOrder() throws ProtocolException {
        SnapshotTransfer transfer = new SnapshotTransfer(begin(2));
        transfer.accept(part(7, 1, BLOCKS[2]));
        transfer.accept(part(7, 0, BLOCKS[0], BLOCKS[1]));
        SnapshotTransfer.Data data = transfer.finish();

        assertArrayEquals(new int[]{0, 1, 127}, data.xs());
        assertArrayEquals(new int[]{0, 2, 300}, data.ys());
        assertArrayEquals(new int[]{0, 3, 128}, data.zs());
        assertArrayEquals(new int[]{0, 1, 0}, data.indices());
    }

    @Test
    void rejectsMissingDuplicateAndForeignParts() throws ProtocolException {
        SnapshotTransfer transfer = new SnapshotTransfer(begin(2));
        transfer.accept(part(7, 0, BLOCKS[0], BLOCKS[1]));
        assertEquals("TRANSFER_INCOMPLETE", code(transfer::finish));
        assertEquals("BAD_MESSAGE", code(() -> transfer.accept(part(7, 0, BLOCKS[2]))));
        assertEquals("BAD_MESSAGE", code(() -> transfer.accept(part(8, 1, BLOCKS[2]))));
        assertEquals("BAD_MESSAGE", code(() -> transfer.accept(part(7, 2, BLOCKS[2]))));
    }

    @Test
    void rejectsMoreBlocksThanDeclared() throws ProtocolException {
        SnapshotTransfer transfer = new SnapshotTransfer(begin(2));
        transfer.accept(part(7, 0, BLOCKS[0], BLOCKS[1]));
        assertEquals("BAD_MESSAGE", code(() -> transfer.accept(part(7, 1, BLOCKS[2], BLOCKS[2]))));
    }

    @Test
    void rejectsMalformedFrames() throws ProtocolException {
        SnapshotTransfer transfer = new SnapshotTransfer(begin(1));
        ByteBuffer bad = part(7, 0, BLOCKS);
        bad.put(0, (byte) 'X');
        assertEquals("BAD_MESSAGE", code(() -> transfer.accept(bad)));
        ByteBuffer truncated = part(7, 0, BLOCKS).limit(SnapshotTransfer.HEADER_BYTES + 5);
        assertEquals("BAD_MESSAGE", code(() -> transfer.accept(truncated)));
    }

    @Test
    void rejectsContentThatDoesNotMatchTheHash() throws ProtocolException {
        SnapshotTransfer transfer = new SnapshotTransfer(begin(1));
        transfer.accept(part(7, 0, BLOCKS[0], new int[]{1, 2, 3, 0}, BLOCKS[2]));
        assertEquals("HASH_MISMATCH", code(transfer::finish));
    }

    @Test
    void rejectsPaletteIndicesAndSizesOutsideTheDeclaration() throws ProtocolException {
        SnapshotTransfer badIndex = new SnapshotTransfer(begin(1));
        badIndex.accept(part(7, 0, BLOCKS[0], new int[]{1, 2, 3, 2}, BLOCKS[2]));
        assertEquals("INVALID_STRUCTURE", code(badIndex::finish));

        SnapshotTransfer badSize = new SnapshotTransfer(new SnapshotTransfer.Begin(7, HASH, 3, 1, PALETTE, 128, 302, 129));
        badSize.accept(part(7, 0, BLOCKS));
        assertEquals("INVALID_STRUCTURE", code(badSize::finish));
    }

    @Test
    void rejectsDeclarationsOutsideTheLimits() {
        assertEquals("LIMIT_EXCEEDED", code(() -> new SnapshotTransfer(
                new SnapshotTransfer.Begin(1, HASH, Limits.MAX_POSITIONS + 1, 3, PALETTE, 128, 10, 129))));
        // 65,536 blocks of 8 bytes need at least 3 parts of 256 KiB.
        assertEquals("LIMIT_EXCEEDED", code(() -> new SnapshotTransfer(
                new SnapshotTransfer.Begin(1, HASH, Limits.MAX_POSITIONS, 2, PALETTE, 128, 10, 129))));
        assertEquals("LIMIT_EXCEEDED", code(() -> new SnapshotTransfer(
                new SnapshotTransfer.Begin(1, HASH, 3, 4, PALETTE, 128, 10, 129))));
        assertEquals("LIMIT_EXCEEDED", code(() -> new SnapshotTransfer(
                new SnapshotTransfer.Begin(1, HASH, 3, 1, PALETTE, 256, 4096, 256))));
        assertEquals("BAD_MESSAGE", code(() -> new SnapshotTransfer(
                new SnapshotTransfer.Begin(0, HASH, 3, 1, PALETTE, 128, 10, 129))));
        assertEquals("BAD_MESSAGE", code(() -> new SnapshotTransfer(
                new SnapshotTransfer.Begin(1, "XYZ", 3, 1, PALETTE, 128, 10, 129))));
    }
}
