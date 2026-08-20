package dev.bifyp.schematicgatherer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Переводит «блок из схематики» в «что реально копать через #mine».
 *
 * Правила работают цепочкой: oak_stairs → oak_planks → oak_log,
 * с накоплением множителя количества (ступеньки 6→8, доски 1→4).
 * Хочешь добавить своё — допиши ветку в цепочку ниже.
 */
public final class ResourceMapper {

    /** Результат: какой блок копать, какой предмет считать, сколько и подсказка для чата. */
    public record Target(Block block, Item item, int amount, String note) {}

    /** Творческие/недобываемые блоки — честно пропускаем с записью в отчёт. */
    private static final Set<String> UNOBTAINABLE = Set.of(
            "bedrock", "barrier", "light",
            "command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "structure_void", "jigsaw",
            "spawner", "trial_spawner", "vault",
            "end_portal", "end_gateway", "nether_portal", "end_portal_frame",
            "reinforced_deepslate", "budding_amethyst"
    );

    private ResourceMapper() {}

    public static Optional<Target> map(Block block, int count) {
        String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
        double mult = 1.0;
        String itemPath = null; // если выпадающий предмет отличается от блока
        List<String> notes = new ArrayList<>();

        for (int guard = 0; guard < 8; guard++) {
            if (UNOBTAINABLE.contains(path)) return Optional.empty();

            String next = null;
            if (path.endsWith("_stairs")) {
                next = firstExisting(strip(path, "_stairs") + "_planks", strip(path, "_stairs"));
                mult *= 0.75; // 6 блоков → 8 ступенек
                notes.add("крафт: ступеньки");
            } else if (path.endsWith("_slab")) {
                next = firstExisting(strip(path, "_slab") + "_planks", strip(path, "_slab"));
                mult *= 0.5; // 1 блок → 2 плиты
                notes.add("крафт: плиты");
            } else if (path.endsWith("_wall")) {
                next = firstExisting(strip(path, "_wall"), strip(path, "_wall") + "_planks");
                notes.add("крафт: ограды");
            } else if (path.endsWith("_fence")) {
                next = firstExisting(strip(path, "_fence") + "_planks");
                notes.add("крафт: заборы");
            } else if (path.equals("bamboo_planks")) {
                next = "bamboo_block";
                mult *= 0.5; // 1 блок бамбука → 2 доски
                notes.add("крафт: доски");
            } else if (path.endsWith("_planks")) {
                next = firstExisting(strip(path, "_planks") + "_log", strip(path, "_planks") + "_stem");
                mult *= 0.25; // 1 бревно → 4 доски
                notes.add("крафт: доски");
            } else if (path.endsWith("_door") || path.endsWith("_trapdoor")) {
                next = firstExisting(strip(path, "_door") + "_planks", strip(path, "_trapdoor") + "_planks");
                notes.add("крафт");
            } else if (path.equals("tinted_glass")) {
                next = "glass";
                notes.add("нужен осколок аметиста (крафт, вручную)");
            } else if (path.equals("glass")) {
                next = "sand";
                notes.add("переплавка: песок → стекло");
            } else if (path.endsWith("_stained_glass")) {
                next = "sand";
                notes.add("переплавка + краситель");
            } else if (path.equals("glass_pane") || path.endsWith("_stained_glass_pane")) {
                next = "sand";
                mult *= 0.375; // 6 стёкол → 16 панелей
                notes.add("переплавка + крафт панелей");
            } else if (path.equals("smooth_stone") || path.equals("stone_bricks")
                    || path.equals("cracked_stone_bricks") || path.equals("chiseled_stone_bricks")) {
                next = "stone";
                notes.add("камень: переплавка/крафт");
            } else if (path.equals("stone")) {
                // копаем камень, но без шёлкового касания выпадает булыжник
                itemPath = "cobblestone";
                notes.add("переплавка: булыжник → камень");
            } else if (path.equals("deepslate")) {
                itemPath = "cobbled_deepslate";
                notes.add("переплавка: булыжный глубосланец → глубосланец");
            } else if (path.equals("bricks")) {
                next = "clay";
                notes.add("переплавка: глина → кирпичи");
            } else if (path.equals("terracotta")) {
                next = "clay";
                notes.add("переплавка: глина → терракота");
            } else if (path.endsWith("_glazed_terracotta")) {
                next = "terracotta";
                notes.add("краситель + обжиг");
            } else if (path.endsWith("_terracotta")) {
                next = "terracotta";
                notes.add("нужен краситель");
            } else if (path.equals("clay")) {
                // копаем блок глины, выпадает 4 шарика
                itemPath = "clay_ball";
                mult *= 4;
            } else if (path.endsWith("_concrete")) {
                next = path + "_powder";
            } else if (path.endsWith("_concrete_powder")) {
                next = "sand";
                mult *= 0.5;
                notes.add("крафт: + гравий и краситель (их добудь вручную)");
            } else if (path.endsWith("_wool")) {
                notes.add("подсказка: шерсть проще стричь с овец");
            } else if (path.equals("grass_block") || path.equals("dirt_path") || path.equals("farmland")) {
                next = "dirt";
                notes.add("без шёлкового касания выпадет земля");
            } else if (path.equals("glowstone")) {
                itemPath = "glowstone_dust";
                mult *= 4;
                notes.add("крафт: пыль → блоки");
            } else if (path.equals("melon")) {
                itemPath = "melon_slice";
                mult *= 4;
                notes.add("крафт: ломтики → блоки");
            } else if (path.equals("bookshelf")) {
                itemPath = "book";
                mult *= 3;
                notes.add("крафт: книги + доски");
            }

            if (next == null) break; // цепочка завершена — копаем текущий блок
            path = next;
        }

        Block mineBlock = byPath(path);
        if (mineBlock == null) return Optional.empty();
        Item countItem = itemPath != null ? itemByPath(itemPath) : mineBlock.asItem();
        if (countItem == null || countItem == Items.AIR) return Optional.empty();
        int amount = Math.max(1, (int) Math.ceil(count * mult));
        return Optional.of(new Target(mineBlock, countItem, amount, String.join("; ", notes)));
    }

    private static String strip(String path, String suffix) {
        return path.endsWith(suffix) ? path.substring(0, path.length() - suffix.length()) : path;
    }

    private static String firstExisting(String... paths) {
        for (String p : paths) {
            if (byPath(p) != null) return p;
        }
        return null;
    }

    /** В 26.1 ResourceLocation переименован в Identifier (net.minecraft.resources.Identifier). */
    private static Identifier id(String path) {
        return Identifier.tryParse(path.contains(":") ? path : "minecraft:" + path);
    }

    private static Block byPath(String path) {
        Identifier key = id(path);
        if (key == null) return null;
        Block b = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        return b == null || b == Blocks.AIR ? null : b;
    }

    private static Item itemByPath(String path) {
        Identifier key = id(path);
        if (key == null) return null;
        Item i = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        return i == null || i == Items.AIR ? null : i;
    }
}
