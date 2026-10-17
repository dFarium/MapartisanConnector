package dfarium.mapartisanconnector.bridge;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dfarium.mapartisanconnector.structure.BuildStructure;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Turns a received snapshot into a BuildStructure. Reads only the frozen block registry,
 * so it can run on the network thread.
 */
public final class SnapshotStructure {

    private SnapshotStructure() {
    }

    public static BuildStructure build(SnapshotTransfer.Data data, String name) throws ProtocolException {
        BlockState[] palette = resolvePalette(data.begin().palette());
        BuildStructure.Builder builder = BuildStructure.builder(name);
        for (int i = 0; i < data.xs().length; i++) {
            builder.add(data.xs()[i], data.ys()[i], data.zs()[i], palette[data.indices()[i]]);
        }
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            // Duplicate positions or limits; nothing was applied.
            throw new ProtocolException("INVALID_STRUCTURE", e.getMessage());
        }
    }

    /** An unknown ID or property rejects the whole revision; nothing is replaced by air (spec section 6). */
    static BlockState[] resolvePalette(List<String> states) throws ProtocolException {
        BlockState[] palette = new BlockState[states.size()];
        for (int i = 0; i < palette.length; i++) {
            try {
                palette[i] = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, states.get(i), false).blockState();
            } catch (CommandSyntaxException e) {
                throw new ProtocolException("INVALID_BLOCK_STATE", "invalid state at palette[" + i + "] '"
                        + states.get(i) + "': " + e.getRawMessage().getString());
            }
        }
        return palette;
    }
}
