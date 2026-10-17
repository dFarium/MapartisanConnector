package dfarium.mapartisanconnector.test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dfarium.mapartisanconnector.litematica.LitematicaPaster;
import dfarium.mapartisanconnector.structure.BuildStructure;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Stage 1 test commands: /mapartisan test origin|paste|verify|suite|abort|status. */
public final class TestCommands {
    private static final String[] STRUCTURES = {"a", "b", "flat", "max"};

    private TestCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
                                LitematicaPaster paster, TestSuite suite) {
        LiteralArgumentBuilder<FabricClientCommandSource> paste = literal("paste");
        for (String name : STRUCTURES) {
            paste.then(literal(name).executes(ctx -> paste(ctx, paster, name)));
        }

        dispatcher.register(literal("mapartisan").then(literal("test")
                .then(literal("origin")
                        .executes(ctx -> origin(ctx, paster, ctx.getSource().getPlayer().blockPosition()))
                        .then(argument("x", IntegerArgumentType.integer())
                                .then(argument("y", IntegerArgumentType.integer())
                                        .then(argument("z", IntegerArgumentType.integer())
                                                .executes(ctx -> origin(ctx, paster, new BlockPos(
                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                        IntegerArgumentType.getInteger(ctx, "z"))))))))
                .then(paste)
                .then(literal("verify").executes(ctx -> verify(ctx, paster)))
                .then(literal("suite").executes(ctx -> suite(ctx, suite)))
                .then(literal("abort").executes(ctx -> abort(ctx, paster)))
                .then(literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(paster.status()));
                    return 1;
                }))));
    }

    private static int origin(CommandContext<FabricClientCommandSource> ctx, LitematicaPaster paster, BlockPos pos) {
        try {
            paster.setOrigin(pos, ctx.getSource().getLevel().dimension());
            ctx.getSource().sendFeedback(Component.literal("Origen fijado en " + pos.toShortString()
                    + ". Noobline en z=" + pos.getZ() + ", píxeles hacia +Z, columnas hacia +X."));
            return 1;
        } catch (IllegalStateException e) {
            ctx.getSource().sendError(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int paste(CommandContext<FabricClientCommandSource> ctx, LitematicaPaster paster, String name) {
        FabricClientCommandSource source = ctx.getSource();
        try {
            BuildStructure structure = SyntheticStructures.byName(name);
            paster.paste(structure, new LitematicaPaster.Listener() {
                @Override
                public void onApplied(LitematicaPaster.PasteReport r) {
                    source.sendFeedback(Component.literal("§a" + r.structureName() + " aplicada§r en " + r.totalMillis()
                            + " ms (espera al holograma " + r.waitMillis() + " ms, pegado " + r.pasteMillis()
                            + " ms, " + r.chunks() + " chunks, reintentos " + r.retries() + ", volumen " + r.volume()
                            + " = " + r.volume().volume() + " posiciones). Usa /mapartisan test verify."));
                }

                @Override
                public void onAborted(LitematicaPaster.PasteReport r) {
                    // Also called while leaving the world, when there is no chat to write to.
                    if (source.getClient().player == null) {
                        return;
                    }
                    source.sendError(Component.literal(r.structureName() + " abortada tras " + r.totalMillis()
                            + " ms (" + r.abortReason() + "): aplicación parcial posible, requiere resincronización."));
                }
            });
            source.sendFeedback(Component.literal("Pegando " + structure.name() + " (" + structure.count()
                    + " bloques, " + structure.sizeX() + "×" + structure.sizeY() + "×" + structure.sizeZ() + ")…"));
            return 1;
        } catch (IllegalStateException | IllegalArgumentException e) {
            source.sendError(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int verify(CommandContext<FabricClientCommandSource> ctx, LitematicaPaster paster) {
        FabricClientCommandSource source = ctx.getSource();
        String name = paster.lastApplied() == null ? "" : paster.lastApplied().name();
        Verification.lastApplied(source.getClient(), paster).whenComplete((r, error) -> {
            if (error != null) {
                source.sendError(Component.literal(error.getMessage()));
            } else if (r.mismatches() == 0) {
                source.sendFeedback(Component.literal("§aÁrea idéntica a " + name + "§r: "
                        + r.checked() + " posiciones en " + r.millis() + " ms."));
            } else {
                source.sendError(Component.literal(r.mismatches() + " diferencias de " + r.checked()
                        + " posiciones (" + r.millis() + " ms):"));
                r.samples().forEach(s -> source.sendError(Component.literal("  " + s)));
            }
        });
        return 1;
    }

    private static int suite(CommandContext<FabricClientCommandSource> ctx, TestSuite suite) {
        try {
            suite.start();
            return 1;
        } catch (IllegalStateException e) {
            ctx.getSource().sendError(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int abort(CommandContext<FabricClientCommandSource> ctx, LitematicaPaster paster) {
        if (paster.abort()) {
            ctx.getSource().sendFeedback(Component.literal("Tarea cancelada; esperando la notificación de aborto de Litematica…"));
            return 1;
        }
        ctx.getSource().sendError(Component.literal("No hay un pegado activo en ejecución"));
        return 0;
    }
}
