package dfarium.mapartisanconnector.bridge;

import dfarium.mapartisanconnector.structure.Bounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * The area a session owns (spec section 7): one map section footprint, 128 wide and 129 deep
 * (noobline plus 128 rows), from the origin up to the build limit. Every paste stays inside it.
 */
public final class ReservedArea {
    public static final int WIDTH = 128;
    public static final int DEPTH = 129;

    /** Non-air blocks found in the area, relative to the origin, and chunks that were not loaded. */
    public record Scan(long occupied, BlockPos first, Bounds occupiedBounds, int unloadedChunks, long millis) {
    }

    private ReservedArea() {
    }

    /** Bounds relative to the origin; maxY is the highest buildable Y of the level. */
    public static Bounds bounds(int originY, int maxY) {
        return new Bounds(0, 0, 0, WIDTH - 1, maxY - originY, DEPTH - 1);
    }

    /** Must run on the server thread. Only reads loaded chunks and skips all-air sections. */
    public static Scan scan(ServerLevel level, BlockPos origin, Bounds area) {
        long start = System.nanoTime();
        int minX = origin.getX() + area.minX(), maxX = origin.getX() + area.maxX();
        int minY = origin.getY() + area.minY(), maxY = origin.getY() + area.maxY();
        int minZ = origin.getZ() + area.minZ(), maxZ = origin.getZ() + area.maxZ();
        long occupied = 0;
        int unloaded = 0;
        BlockPos first = null;
        int oMinX = Integer.MAX_VALUE, oMinY = Integer.MAX_VALUE, oMinZ = Integer.MAX_VALUE;
        int oMaxX = Integer.MIN_VALUE, oMaxY = Integer.MIN_VALUE, oMaxZ = Integer.MIN_VALUE;

        for (int cx = SectionPos.blockToSectionCoord(minX); cx <= SectionPos.blockToSectionCoord(maxX); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(minZ); cz <= SectionPos.blockToSectionCoord(maxZ); cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    unloaded++;
                    continue;
                }
                int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
                int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
                for (int sy = SectionPos.blockToSectionCoord(minY); sy <= SectionPos.blockToSectionCoord(maxY); sy++) {
                    LevelChunkSection section = chunk.getSection(level.getSectionIndexFromSectionY(sy));
                    if (section.hasOnlyAir()) {
                        continue;
                    }
                    int y0 = Math.max(minY, sy << 4), y1 = Math.min(maxY, (sy << 4) + 15);
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (state.isAir()) {
                                    continue;
                                }
                                occupied++;
                                if (first == null) {
                                    first = new BlockPos(x, y, z);
                                }
                                int rx = x - origin.getX(), ry = y - origin.getY(), rz = z - origin.getZ();
                                oMinX = Math.min(oMinX, rx);
                                oMinY = Math.min(oMinY, ry);
                                oMinZ = Math.min(oMinZ, rz);
                                oMaxX = Math.max(oMaxX, rx);
                                oMaxY = Math.max(oMaxY, ry);
                                oMaxZ = Math.max(oMaxZ, rz);
                            }
                        }
                    }
                }
            }
        }
        Bounds occupiedBounds = occupied == 0 ? null : new Bounds(oMinX, oMinY, oMinZ, oMaxX, oMaxY, oMaxZ);
        return new Scan(occupied, first, occupiedBounds, unloaded, (System.nanoTime() - start) / 1_000_000L);
    }
}
