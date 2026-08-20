package dev.bifyp.schematicgatherer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиентская точка входа.
 *
 * ВАЖНО: этот класс НЕ ссылается на baritone.* напрямую. Baritone —
 * опциональная зависимость (suggests), и если его jar нет в mods/, любое
 * обращение к baritone.api.* роняет игру с NoClassDefFoundError прямо на
 * инициализации (GatherProcess implements baritone.api.utils.Helper).
 * Поэтому вся работа с Baritone живёт в BaritoneHook, который грузится
 * только после проверки classpath.
 */
public final class SchematicGathererMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("SchematicGatherer");

    @Override
    public void onInitializeClient() {
        if (!baritoneOnClasspath()) {
            LOGGER.warn("[SchematicGatherer] Baritone не найден -> команда #gather отключена. "
                    + "Серверная часть (/gatherbot) работает как обычно.");
            return;
        }
        try {
            BaritoneHook.install();
        } catch (Throwable t) {
            LOGGER.error("[SchematicGatherer] не смог подключиться к Baritone -> #gather отключена", t);
        }
    }

    /** Проверяем именно классы, а не id мода: у форков Baritone id бывает другой. */
    private static boolean baritoneOnClasspath() {
        boolean present = classPresent("baritone.api.BaritoneAPI") && classPresent("baritone.api.utils.Helper");
        if (!present && FabricLoader.getInstance().isModLoaded("baritone")) {
            LOGGER.warn("[SchematicGatherer] мод baritone есть, но baritone.api.* недоступен — "
                    + "похоже, в mods/ лежит standalone-сборка (там api обфусцирован). "
                    + "Нужен baritone-api-fabric-*.jar или baritone-unoptimized-fabric-*.jar");
        }
        return present;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, SchematicGathererMod.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
