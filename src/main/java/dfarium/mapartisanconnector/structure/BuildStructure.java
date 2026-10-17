package dfarium.mapartisanconnector.structure;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable construction snapshot: a palette of block states plus explicit block positions.
 * Positions are local, non-negative and unique; every other position in the bounds is air.
 */
public final class BuildStructure {
    /** Limits from docs/litematica-live-preview-spec.md, section 10. */
    public static final int MAX_POSITIONS = 65_536;
    public static final long MAX_VOLUME = 8_000_000L;

    private final String name;
    private final BlockState[] palette;
    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final int[] paletteIndices;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    private BuildStructure(String name, BlockState[] palette, int[] xs, int[] ys, int[] zs, int[] paletteIndices) {
        if (xs.length != ys.length || xs.length != zs.length || xs.length != paletteIndices.length) {
            throw new IllegalArgumentException("Position and palette index arrays differ in length");
        }
        if (xs.length == 0) {
            throw new IllegalArgumentException("The structure has no blocks");
        }
        if (xs.length > MAX_POSITIONS) {
            throw new IllegalArgumentException("Too many positions: " + xs.length + " > " + MAX_POSITIONS);
        }

        int maxX = 0, maxY = 0, maxZ = 0;
        for (int i = 0; i < xs.length; i++) {
            if (xs[i] < 0 || ys[i] < 0 || zs[i] < 0) {
                throw new IllegalArgumentException("Negative position at index " + i);
            }
            if (paletteIndices[i] < 0 || paletteIndices[i] >= palette.length || palette[paletteIndices[i]] == null) {
                throw new IllegalArgumentException("Invalid palette index at position " + i);
            }
            maxX = Math.max(maxX, xs[i]);
            maxY = Math.max(maxY, ys[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }

        long volume = (long) (maxX + 1) * (maxY + 1) * (maxZ + 1);
        if (volume > MAX_VOLUME) {
            throw new IllegalArgumentException("Volume too large: " + volume + " > " + MAX_VOLUME);
        }

        BitSet seen = new BitSet((int) volume);
        for (int i = 0; i < xs.length; i++) {
            int key = (ys[i] * (maxZ + 1) + zs[i]) * (maxX + 1) + xs[i];
            if (seen.get(key)) {
                throw new IllegalArgumentException("Duplicate position " + xs[i] + "," + ys[i] + "," + zs[i]);
            }
            seen.set(key);
        }

        this.name = name;
        this.palette = palette;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.paletteIndices = paletteIndices;
        this.sizeX = maxX + 1;
        this.sizeY = maxY + 1;
        this.sizeZ = maxZ + 1;
    }

    public String name() {
        return name;
    }

    public int count() {
        return xs.length;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int x(int i) {
        return xs[i];
    }

    public int y(int i) {
        return ys[i];
    }

    public int z(int i) {
        return zs[i];
    }

    public BlockState state(int i) {
        return palette[paletteIndices[i]];
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> paletteIds = new HashMap<>();
        private final IntArrayList xs = new IntArrayList();
        private final IntArrayList ys = new IntArrayList();
        private final IntArrayList zs = new IntArrayList();
        private final IntArrayList indices = new IntArrayList();

        private Builder(String name) {
            this.name = name;
        }

        public Builder add(int x, int y, int z, BlockState state) {
            int id = paletteIds.computeIfAbsent(state, s -> {
                palette.add(s);
                return palette.size() - 1;
            });
            xs.add(x);
            ys.add(y);
            zs.add(z);
            indices.add(id);
            return this;
        }

        public BuildStructure build() {
            return new BuildStructure(name, palette.toArray(BlockState[]::new),
                    xs.toIntArray(), ys.toIntArray(), zs.toIntArray(), indices.toIntArray());
        }
    }
}
