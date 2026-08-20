package dev.bifyp.schematicgatherer;

import dev.bifyp.schematicgatherer.bot.GatherBotCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/** Общая (серверная) инициализация: команда /gatherbot. */
public final class SchematicGathererInit implements ModInitializer {

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                GatherBotCommand.register(dispatcher));
    }
}
