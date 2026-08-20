package dev.bifyp.schematicgatherer;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа. Регистрирует команду #gather в командный менеджер Baritone
 * (лениво, на первом тике — когда primary-инстанс точно создан)
 * и гоняет конечный автомат сбора каждый клиентский тик.
 */
public final class SchematicGathererMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("SchematicGatherer");

    private final GatherProcess gatherProcess = new GatherProcess();
    private boolean commandRegistered = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!commandRegistered) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getCommandManager().getRegistry().register(new GatherCommand(baritone, gatherProcess));
                    commandRegistered = true;
                    LOGGER.info("[SchematicGatherer] команда #gather зарегистрирована");
                }
            }
            gatherProcess.tick();
        });
    }
}
