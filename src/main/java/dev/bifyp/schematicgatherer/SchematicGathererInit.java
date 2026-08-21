package dev.bifyp.schematicgatherer;

import dev.bifyp.schematicgatherer.bot.BotPersistence;
import dev.bifyp.schematicgatherer.bot.GatherBotCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Общая (серверная) инициализация: команда /gatherbot + боты переживают рестарт. */
public final class SchematicGathererInit implements ModInitializer {

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                GatherBotCommand.register(dispatcher));
        // schematic-gatherer-bots.json: боты и их склады поднимаются после рестарта
        ServerLifecycleEvents.SERVER_STOPPING.register(BotPersistence::save);
        ServerLifecycleEvents.SERVER_STARTED.register(BotPersistence::load);
    }
}
