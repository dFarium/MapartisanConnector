package dfarium.mapartisanconnector.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dfarium.mapartisanconnector.bridge.ConnectorSession;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** /mapartisan start [x y z] | stop | status: the session MapArtisan connects to (spec section 12.2). */
public final class ConnectorCommands {

    private ConnectorCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, ConnectorSession session) {
        dispatcher.register(literal("mapartisan")
                .then(literal("start")
                        .executes(ctx -> start(ctx, session, ctx.getSource().getPlayer().blockPosition()))
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> start(ctx, session, new BlockPos(
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))))
                .then(literal("stop").executes(ctx -> {
                    if (session.stop("session stopped in game")) {
                        ctx.getSource().sendFeedback(Component.literal("Connector session closed; placed blocks stay in the world."));
                        return 1;
                    }
                    ctx.getSource().sendError(Component.literal("No connector session is running"));
                    return 0;
                }))
                .then(literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(session.status()));
                    return 1;
                })));
    }

    private static int start(CommandContext<FabricClientCommandSource> ctx, ConnectorSession session, BlockPos origin) {
        try {
            ctx.getSource().sendFeedback(session.start(origin));
            return 1;
        } catch (IllegalStateException e) {
            ctx.getSource().sendError(Component.literal(e.getMessage()));
            return 0;
        }
    }
}
