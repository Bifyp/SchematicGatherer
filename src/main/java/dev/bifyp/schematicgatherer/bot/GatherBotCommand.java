package dev.bifyp.schematicgatherer.bot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.bifyp.schematicgatherer.ResourceMapper;
import dev.bifyp.schematicgatherer.schematic.SpongeSchematic;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /gatherbot — управление ботом-сборщиком (фейковый игрок в стиле Carpet /player). */
public final class GatherBotCommand {

    private static final int DEFAULT_RADIUS = 48;

    private GatherBotCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("gatherbot")
                // в 26.x проверка прав идёт через PermissionSource, а не hasPermission(int)
                .requires(src -> Commands.LEVEL_GAMEMASTERS.check(src.permissions()))
                .then(literal("spawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(c -> spawn(c, c.getSource().getPosition()))
                                .then(literal("at")
                                        .then(argument("position", Vec3Argument.vec3())
                                                .executes(c -> spawn(c, Vec3Argument.getVec3(c, "position")))))))
                .then(argument("bot", StringArgumentType.word())
                        .then(literal("kill").executes(GatherBotCommand::kill))
                        .then(literal("stop").executes(GatherBotCommand::stop))
                        .then(literal("status").executes(GatherBotCommand::status))
                        .then(literal("list")
                                .then(argument("schematic", StringArgumentType.word())
                                        .executes(GatherBotCommand::list)))
                        .then(literal("gather")
                                .then(argument("schematic", StringArgumentType.word())
                                        .executes(c -> gather(c, DEFAULT_RADIUS))
                                        .then(argument("radius", IntegerArgumentType.integer(8, 128))
                                                .executes(c -> gather(c, IntegerArgumentType.getInteger(c, "radius"))))))));
    }

    private static int spawn(CommandContext<CommandSourceStack> c, Vec3 pos) {
        String name = StringArgumentType.getString(c, "name");
        MinecraftServer server = c.getSource().getServer();
        if (server.getPlayerList().getPlayerByName(name) != null) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] «" + name + "» уже в игре"));
            return 0;
        }
        ServerLevel level = c.getSource().getLevel();
        Vec2 rot = c.getSource().getRotation();
        GatherBot.spawn(name, server, level, pos, rot.y, rot.x);
        c.getSource().sendSystemMessage(Component.literal("§a[бот] «" + name + "» заспавнен. Выдай ему кирку и команду gather :)"));
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        bot.brain.stopJob(false);
        bot.kill(Component.literal("Killed by command"));
        c.getSource().sendSystemMessage(Component.literal("§e[бот] «" + bot.getGameProfile().name() + "» отключён"));
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        bot.brain.stopJob(true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        c.getSource().sendSystemMessage(Component.literal("§7[бот] " + bot.brain.status()));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        BuildResult res = readAndPlan(c);
        if (res == null) return 0;
        c.getSource().sendSystemMessage(Component.literal("§7[бот] материалы «" + res.name() + "» (позиций: " + res.targets().size() + "):"));
        res.targets().stream().limit(20).forEach(t ->
                c.getSource().sendSystemMessage(Component.literal("§7 - " + t.label() + " ×" + t.needed()
                        + (t.note().isEmpty() ? "" : " — " + t.note()))));
        if (res.targets().size() > 20) {
            c.getSource().sendSystemMessage(Component.literal("§7 …и ещё " + (res.targets().size() - 20)));
        }
        return 1;
    }

    private static int gather(CommandContext<CommandSourceStack> c, int radius) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        if (bot.brain.isRunning()) {
            c.getSource().sendSystemMessage(Component.literal("§e[бот] уже занят. Сначала: /gatherbot " + bot.getGameProfile().name() + " stop"));
            return 0;
        }
        BuildResult res = readAndPlan(c);
        if (res == null) return 0;
        if (res.targets().isEmpty()) {
            c.getSource().sendSystemMessage(Component.literal("§e[бот] по схеме нечего добывать"));
            return 0;
        }
        UUID owner = c.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        bot.brain.startJob(res.name(), res.targets(), owner, radius);
        return 1;
    }

    private record BuildResult(String name, List<GatherTarget> targets) {}

    private static BuildResult readAndPlan(CommandContext<CommandSourceStack> c) {
        String fileName = StringArgumentType.getString(c, "schematic");
        File file = new File(new File("schematics"), fileName);
        if (!fileName.endsWith(".schem")) {
            file = new File(new File("schematics"), fileName + ".schem");
        }
        if (!file.exists()) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] нет файла schematics/" + file.getName()));
            return null;
        }
        try {
            MinecraftServer server = c.getSource().getServer();
            SpongeSchematic schem = SpongeSchematic.read(file, server.registryAccess().lookupOrThrow(Registries.BLOCK));
            return new BuildResult(file.getName(), planTargets(schem));
        } catch (Exception e) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] не смог прочитать " + file.getName() + ": " + e.getMessage()));
            return null;
        }
    }

    /** Схема → очередь целей (подсчёт блоков + цепочки ResourceMapper). */
    private static List<GatherTarget> planTargets(SpongeSchematic schem) {
        Map<Block, Integer> counts = new HashMap<>();
        for (int y = 0; y < schem.height(); y++) {
            for (int z = 0; z < schem.length(); z++) {
                for (int x = 0; x < schem.width(); x++) {
                    BlockState state = schem.stateAt(x, y, z);
                    if (state == null || state.isAir()) continue;
                    Block block = state.getBlock();
                    if (block.asItem() == Items.AIR) continue; // вода/лава/огонь
                    counts.merge(block, 1, Integer::sum);
                }
            }
        }

        Map<String, GatherTarget> merged = new LinkedHashMap<>();
        for (Map.Entry<Block, Integer> e : counts.entrySet()) {
            Optional<ResourceMapper.Target> mapped = ResourceMapper.map(e.getKey(), e.getValue());
            if (mapped.isEmpty()) continue; // недобываемое — пропускаем
            ResourceMapper.Target t = mapped.get();
            String key = BuiltInRegistries.BLOCK.getKey(t.block()).getPath()
                    + "->" + BuiltInRegistries.ITEM.getKey(t.item()).getPath();
            GatherTarget existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new GatherTarget(t.block(), t.item(), t.amount(),
                        BuiltInRegistries.ITEM.getKey(t.item()).getPath(), t.note()));
            } else {
                merged.put(key, new GatherTarget(existing.block(), existing.item(),
                        existing.needed() + t.amount(), existing.label(), existing.note()));
            }
        }
        List<GatherTarget> targets = new ArrayList<>(merged.values());
        targets.sort((a, b) -> Integer.compare(b.needed(), a.needed()));
        return targets;
    }

    private static GatherBot getBot(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "bot");
        ServerPlayer player = c.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (player instanceof GatherBot bot) return bot;
        c.getSource().sendSystemMessage(Component.literal("§c[бот] «" + name + "» не найден (или это не наш бот)"));
        return null;
    }
}
