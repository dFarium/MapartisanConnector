package dfarium.mapartisanconnector.test;

import dfarium.mapartisanconnector.litematica.LitematicaPaster;
import dfarium.mapartisanconnector.structure.Bounds;
import dfarium.mapartisanconnector.structure.BuildStructure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.concurrent.CompletableFuture;

/** Runs AreaVerifier for the last applied revision on the server thread. */
public final class Verification {

    private Verification() {
    }

    /** Completes on the client thread; fails with IllegalStateException when nothing can be verified. */
    public static CompletableFuture<AreaVerifier.Result> lastApplied(Minecraft mc, LitematicaPaster paster) {
        CompletableFuture<AreaVerifier.Result> future = new CompletableFuture<>();
        BuildStructure structure = paster.lastApplied();
        Bounds volume = paster.lastVolume();
        BlockPos origin = paster.origin();
        ResourceKey<Level> dimension = paster.dimension();
        IntegratedServer server = mc.getSingleplayerServer();

        if (paster.isPasting()) {
            future.completeExceptionally(new IllegalStateException("Hay un pegado activo; verifica cuando termine"));
            return future;
        }
        if (structure == null || server == null) {
            future.completeExceptionally(new IllegalStateException("No hay ninguna revisión aplicada en esta sesión"));
            return future;
        }

        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                mc.execute(() -> future.completeExceptionally(new IllegalStateException("La dimensión ya no existe")));
                return;
            }
            AreaVerifier.Result result = AreaVerifier.verify(level, origin, volume, structure);
            mc.execute(() -> future.complete(result));
        });
        return future;
    }
}
