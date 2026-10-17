package dfarium.mapartisanconnector.verify;

import dfarium.mapartisanconnector.structure.Bounds;
import dfarium.mapartisanconnector.structure.BuildStructure;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Compares the world against a structure: inside the volume, every position without a block must be air. */
public final class AreaVerifier {
    private static final int MAX_SAMPLES = 5;

    public record Result(long checked, long mismatches, List<String> samples, long millis) {
    }

    private AreaVerifier() {
    }

    /** Must run on the server thread. Volume and structure positions are relative to the origin. */
    public static Result verify(ServerLevel level, BlockPos origin, Bounds volume, BuildStructure structure) {
        long start = System.nanoTime();
        Long2ObjectOpenHashMap<BlockState> expected = new Long2ObjectOpenHashMap<>(structure.count());
        for (int i = 0; i < structure.count(); i++) {
            expected.put(BlockPos.asLong(structure.x(i), structure.y(i), structure.z(i)), structure.state(i));
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        List<String> samples = new ArrayList<>();
        long checked = 0;
        long mismatches = 0;

        for (int y = volume.minY(); y <= volume.maxY(); y++) {
            for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                for (int x = volume.minX(); x <= volume.maxX(); x++) {
                    BlockState want = expected.getOrDefault(BlockPos.asLong(x, y, z), air);
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState actual = level.getBlockState(pos);
                    checked++;
                    if (!actual.equals(want)) {
                        mismatches++;
                        if (samples.size() < MAX_SAMPLES) {
                            samples.add(x + "," + y + "," + z + ": expected " + want + ", found " + actual);
                        }
                    }
                }
            }
        }
        return new Result(checked, mismatches, samples, (System.nanoTime() - start) / 1_000_000L);
    }
}
