package dfarium.mapartisanconnector.client;

import dfarium.mapartisanconnector.bridge.ConnectorSession;
import dfarium.mapartisanconnector.litematica.LitematicaPaster;
import dfarium.mapartisanconnector.test.TestCommands;
import dfarium.mapartisanconnector.test.TestSuite;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class MapartisanConnectorClient implements ClientModInitializer {
    private static final LitematicaPaster PASTER = new LitematicaPaster();
    private static final TestSuite SUITE = new TestSuite(PASTER);
    private static final ConnectorSession SESSION = new ConnectorSession(PASTER);

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            TestCommands.register(dispatcher, PASTER, SUITE);
            ConnectorCommands.register(dispatcher, SESSION);
        });
        // World exit is detected here (level == null) because Fabric's DISCONNECT event runs on a Netty thread.
        // The paster reports an interrupted paste before the session closes the connection.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SUITE.tick(client);
            PASTER.tick(client);
            SESSION.tick(client);
        });
        // Litematica only clears its client scheduler on world change; our server-side task would be left behind.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PASTER.onServerStopping());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SESSION.stop("game closed"));
    }
}
