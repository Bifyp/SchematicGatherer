package dev.bifyp.schematicgatherer;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Всё, что трогает baritone.api.*, живёт только здесь.
 *
 * Класс загружается ЛЕНИВО — только из SchematicGathererMod#onInitializeClient
 * и только после проверки, что Baritone реально есть в classpath.
 * Никогда не ссылайся на этот класс из полей/подписей методов других классов.
 */
final class BaritoneHook {

    private BaritoneHook() {}

    static void install() {
        final GatherProcess gatherProcess = new GatherProcess();
        final boolean[] registered = {false};

        // Команду регистрируем лениво, на первом тике: primary-инстанс Baritone
        // создаётся позже нашего onInitializeClient.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!registered[0]) {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone != null) {
                    baritone.getCommandManager().getRegistry().register(new GatherCommand(baritone, gatherProcess));
                    registered[0] = true;
                    SchematicGathererMod.LOGGER.info("[SchematicGatherer] команда #gather зарегистрирована");
                }
            }
            gatherProcess.tick();
        });
    }
}
