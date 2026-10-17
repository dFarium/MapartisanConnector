package dfarium.mapartisanconnector.test;

import dfarium.mapartisanconnector.structure.BuildStructure;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Test structures with MapArtisan's export layout: 128 columns along X, the noobline at z = 0
 * and pixel rows at z = 1..128, supports directly below blocks.
 *
 * Columns 0-2 exercise blocks that break when pasted carelessly: sand (gravity),
 * carpet (needs a block below) and leaves (decay unless persistent).
 */
public final class SyntheticStructures {
    private static final int WIDTH = 128;
    private static final int ROWS = 128;
    private static final List<Block> COLORS = Blocks.CONCRETE.asList();
    private static final BlockState SUPPORT = Blocks.COBBLESTONE.defaultBlockState();

    private SyntheticStructures() {
    }

    public static BuildStructure byName(String name) {
        return switch (name) {
            case "a" -> revisionA();
            case "b" -> revisionB();
            case "flat" -> flat();
            case "max" -> maxVolume();
            default -> throw new IllegalArgumentException("Unknown structure: " + name);
        };
    }

    /** Valley-like heights up to 12 with a support below every raised block. */
    public static BuildStructure revisionA() {
        return terrain("A", 12, 0, true);
    }

    /**
     * Lower heights, shifted colours and supports only where blocks need them.
     * Pasted after A it must remove raised blocks and most supports.
     */
    public static BuildStructure revisionB() {
        return terrain("B", 4, 5, false);
    }

    /** 2D map: every block at y = 0, no supports. */
    public static BuildStructure flat() {
        BuildStructure.Builder b = BuildStructure.builder("flat");
        for (int x = 0; x < WIDTH; x++) {
            b.add(x, 0, 0, SUPPORT);
            for (int z = 1; z <= ROWS; z++) {
                b.add(x, 0, z, color(x, z, 9));
            }
        }
        return b.build();
    }

    /** Staircase reaching y = 254: about 4.2 million positions in the paste volume. */
    public static BuildStructure maxVolume() {
        BuildStructure.Builder b = BuildStructure.builder("max");
        for (int x = 0; x < WIDTH; x++) {
            b.add(x, 0, 0, SUPPORT);
            for (int z = 1; z <= ROWS; z++) {
                int y = (z - 1) * 2;
                b.add(x, y, z, color(x, z, 0));
                if (y > 0) {
                    b.add(x, y - 1, z, SUPPORT);
                }
            }
        }
        return b.build();
    }

    private static BuildStructure terrain(String name, int amplitude, int colorShift, boolean supportAll) {
        BuildStructure.Builder b = BuildStructure.builder(name);
        for (int x = 0; x < WIDTH; x++) {
            b.add(x, 0, 0, SUPPORT);
            for (int z = 1; z <= ROWS; z++) {
                int y = triangle(x + z, amplitude);
                BlockState state = switch (x) {
                    case 0 -> Blocks.SAND.defaultBlockState();
                    case 1 -> Blocks.CARPET.pick(DyeColor.RED).defaultBlockState();
                    case 2 -> Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
                    default -> color(x, z, colorShift);
                };
                b.add(x, y, z, state);

                boolean needsSupport = x <= 1;
                if (y > 0 && (supportAll || needsSupport)) {
                    b.add(x, y - 1, z, SUPPORT);
                }
            }
        }
        return b.build();
    }

    private static int triangle(int t, int amplitude) {
        int period = amplitude * 2;
        int phase = Math.floorMod(t, period);
        return phase <= amplitude ? phase : period - phase;
    }

    private static BlockState color(int x, int z, int shift) {
        return COLORS.get(Math.floorMod(x / 8 + z / 8 + shift, COLORS.size())).defaultBlockState();
    }
}
