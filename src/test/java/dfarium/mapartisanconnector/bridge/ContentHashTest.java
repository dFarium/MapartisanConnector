package dfarium.mapartisanconnector.bridge;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContentHashTest {

    @Test
    void matchesPublishedFnv1a64Values() {
        assertEquals("cbf29ce484222325", ContentHash.of(new byte[0]));
        assertEquals("af63dc4c8601ec8c", ContentHash.of("a".getBytes(StandardCharsets.US_ASCII)));
    }

    /** Same vector as MapArtisan's src/utils/__tests__/snapshot.test.ts. */
    @Test
    void matchesMapArtisanSnapshotHash() {
        List<String> palette = List.of("minecraft:stone", "minecraft:oak_leaves[persistent=true]");
        String hash = ContentHash.of(palette,
                new int[]{0, 1, 127}, new int[]{0, 2, 300}, new int[]{0, 3, 128}, new int[]{0, 1, 0});
        assertEquals("4647d62b64c2a609", hash);
    }
}
