package dev.bifyp.schematicgatherer.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/**
 * schematic-gatherer-bots.json в корне запуска: боты и их настройки переживают рестарт.
 * Сохраняем при спавне/kill/смене склада/зоны/защиты и на SERVER_STOPPING,
 * поднимаем на SERVER_STARTED.
 */
public final class BotPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File("schematic-gatherer-bots.json");

    private BotPersistence() {}

    public static void save(MinecraftServer server) {
        JsonArray arr = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!(p instanceof GatherBot bot)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("name", bot.getGameProfile().name());
            o.addProperty("dim", dimId((ServerLevel) bot.level()));
            o.addProperty("x", bot.getX());
            o.addProperty("y", bot.getY());
            o.addProperty("z", bot.getZ());
            o.addProperty("yaw", bot.getYRot());
            o.addProperty("pitch", bot.getXRot());
            BlockPos dep = bot.brain.getDeposit();
            if (dep != null) {
                o.addProperty("deposit", dep.getX() + "," + dep.getY() + "," + dep.getZ());
            }
            o.addProperty("protect", bot.brain.getProtectRadius());
            BlockPos a = bot.brain.getRegionA();
            BlockPos b = bot.brain.getRegionB();
            if (a != null && b != null) {
                o.addProperty("region", a.getX() + "," + a.getY() + "," + a.getZ() + ";"
                        + b.getX() + "," + b.getY() + "," + b.getZ());
            }
            arr.add(o);
        }
        try (Writer w = new FileWriter(FILE)) {
            GSON.toJson(arr, w);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void load(MinecraftServer server) {
        if (!FILE.exists()) return;
        JsonArray arr;
        try (Reader r = new FileReader(FILE)) {
            arr = JsonParser.parseReader(r).getAsJsonArray();
        } catch (Exception e) {
            return; // битый/пустой файл — просто никого не поднимаем
        }
        for (JsonElement el : arr) {
            try {
                JsonObject o = el.getAsJsonObject();
                String name = o.get("name").getAsString();
                if (server.getPlayerList().getPlayerByName(name) != null) continue;
                Identifier dim = Identifier.tryParse(o.get("dim").getAsString());
                ServerLevel level = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
                if (level == null) level = server.overworld();
                GatherBot bot = GatherBot.spawn(name, server, level,
                        new Vec3(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble()),
                        o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat());
                if (o.has("deposit")) {
                    bot.brain.setDeposit(parsePos(o.get("deposit").getAsString()));
                }
                if (o.has("protect")) {
                    bot.brain.setProtectRadius(o.get("protect").getAsInt());
                }
                if (o.has("region")) {
                    String[] two = o.get("region").getAsString().split(";");
                    if (two.length == 2) {
                        bot.brain.setRegionA(parsePos(two[0]));
                        bot.brain.setRegionB(parsePos(two[1]));
                    }
                }
            } catch (Exception ignored) {
                // битая запись — пропускаем, остальных поднимаем
            }
        }
    }

    private static BlockPos parsePos(String s) {
        String[] parts = s.split(",");
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /** Без обращения к переименованным в 26.1 методам ResourceKey — покрываем ванильные измерения. */
    private static String dimId(ServerLevel level) {
        if (level.getServer().getLevel(Level.NETHER) == level) return "minecraft:nether";
        if (level.getServer().getLevel(Level.END) == level) return "minecraft:the_end";
        return "minecraft:overworld";
    }
}
