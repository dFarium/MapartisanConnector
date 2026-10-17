package dfarium.mapartisanconnector.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dfarium.mapartisanconnector.bridge.ConnectorSession;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * /mapartisan start [x y z] [force] | stop | status: the session MapArtisan connects to
 * (spec sections 12.2 and 12.3). force accepts a reserved area that is not empty.
 */
public final class ConnectorCommands {

    private ConnectorCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, ConnectorSession session) {
        LiteralArgumentBuilder<FabricClientCommandSource> start = literal("start")
                .executes(ctx -> start(ctx, session, here(ctx), false))
                .then(literal("force").executes(ctx -> start(ctx, session, here(ctx), true)))
                .then(argument("x", IntegerArgumentType.integer())
                        .then(argument("y", IntegerArgumentType.integer())
                                .then(argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> start(ctx, session, at(ctx), false))
                                        .then(literal("force").executes(ctx -> start(ctx, session, at(ctx), true))))));

        dispatcher.register(literal("mapartisan")
                .then(start)
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

    private static BlockPos here(CommandContext<FabricClientCommandSource> ctx) {
        return ctx.getSource().getPlayer().blockPosition();
    }

    private static BlockPos at(CommandContext<FabricClientCommandSource> ctx) {
        return new BlockPos(IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"));
    }

    private static int start(CommandContext<FabricClientCommandSource> ctx, ConnectorSession session, BlockPos origin,
                             boolean force) {
        FabricClientCommandSource source = ctx.getSource();
        try {
            session.start(origin, force, source::sendFeedback, message -> source.sendError(Component.literal(message)));
            source.sendFeedback(Component.literal("Checking the reserved area…"));
            return 1;
        } catch (IllegalStateException e) {
            source.sendError(Component.literal(e.getMessage()));
            return 0;
        }
    }
}
