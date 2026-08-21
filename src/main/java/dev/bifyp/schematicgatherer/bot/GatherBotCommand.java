package dev.bifyp.schematicgatherer.bot;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.bifyp.schematicgatherer.ResourceMapper;
import dev.bifyp.schematicgatherer.schematic.SpongeSchematic;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
    private static final double DEPOSIT_RAYCAST = 6.0;
    private static final int DEFAULT_COLLECT_AMOUNT = 64;

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
                        .then(literal("pause").executes(GatherBotCommand::pause))
                        .then(literal("resume").executes(GatherBotCommand::resume))
                        .then(literal("status").executes(GatherBotCommand::status))
                        .then(literal("skip")
                                .executes(GatherBotCommand::skipCurrent)
                                .then(argument("item", StringArgumentType.word())
                                        .executes(GatherBotCommand::skipItem)))
                        .then(literal("collect")
                                .then(argument("block", StringArgumentType.word())
                                        .executes(c -> collect(c, DEFAULT_COLLECT_AMOUNT))
                                        .then(argument("amount", IntegerArgumentType.integer(1, 100000))
                                                .executes(c -> collect(c, IntegerArgumentType.getInteger(c, "amount"))))))
                        .then(literal("list")
                                .then(argument("schematic", StringArgumentType.word())
                                        .executes(GatherBotCommand::list)))
                        .then(literal("gather")
                                .then(argument("schematic", StringArgumentType.word())
                                        .executes(c -> gather(c, DEFAULT_RADIUS))
                                        .then(argument("radius", IntegerArgumentType.integer(8, 128))
                                                .executes(c -> gather(c, IntegerArgumentType.getInteger(c, "radius"))))))
                        .then(literal("deposit")
                                .executes(GatherBotCommand::depositInfo)
                                .then(literal("here").executes(GatherBotCommand::depositHere))
                                .then(literal("now").executes(GatherBotCommand::depositNow))
                                .then(literal("clear").executes(GatherBotCommand::depositClear))
                                .then(literal("set")
                                        .then(argument("position", BlockPosArgument.blockPos())
                                                .executes(GatherBotCommand::depositSet))))));
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
        BotPersistence.save(server);
        c.getSource().sendSystemMessage(Component.literal("§a[бот] «" + name + "» заспавнен (неуязвим до kill, переживает рестарт). "
                + "Выдай кирку, задай склад (deposit here) и команду gather/collect :)"));
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        bot.brain.stopJob(false);
        bot.kill(Component.literal("Killed by command"));
        BotPersistence.save(c.getSource().getServer());
        c.getSource().sendSystemMessage(Component.literal("§e[бот] «" + bot.getGameProfile().name() + "» отключён"));
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        bot.brain.stopJob(true);
        return 1;
    }

    private static int pause(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        bot.brain.setPaused(true);
        c.getSource().sendSystemMessage(Component.literal("§e[бот] «" + bot.getGameProfile().name()
                + "» на паузе (прогресс сохранён). Продолжить: /gatherbot " + bot.getGameProfile().name() + " resume"));
        return 1;
    }

    private static int resume(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        bot.brain.setPaused(false);
        c.getSource().sendSystemMessage(Component.literal("§a[бот] «" + bot.getGameProfile().name() + "» продолжает"));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        c.getSource().sendSystemMessage(Component.literal("§7[бот] " + bot.brain.status()));
        return 1;
    }

    // ---------- skip ----------

    private static int skipCurrent(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        if (!bot.brain.skipCurrent()) {
            c.getSource().sendSystemMessage(Component.literal("§e[бот] нет активной цели — нечего скипать"));
            return 0;
        }
        return 1;
    }

    private static int skipItem(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        String id = StringArgumentType.getString(c, "item");
        int removed = bot.brain.skipById(id);
        c.getSource().sendSystemMessage(Component.literal(removed == 0
                ? "§7[бот] в плане нет «" + id + "»"
                : "§e[бот] вычеркнуто позиций: " + removed));
        return removed > 0 ? 1 : 0;
    }

    // ---------- сбор конкретного ресурса без схематики ----------

    private static int collect(CommandContext<CommandSourceStack> c, int amount) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        if (bot.brain.isRunning()) {
            c.getSource().sendSystemMessage(Component.literal("§e[бот] уже занят. Сначала: /gatherbot " + bot.getGameProfile().name() + " stop"));
            return 0;
        }
        String id = StringArgumentType.getString(c, "block");
        Identifier identifier = Identifier.tryParse(id);
        Block block = identifier == null ? null : BuiltInRegistries.BLOCK.getOptional(identifier).orElse(null);
        if (block == null || block == Blocks.AIR) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] не знаю блок «" + id + "» (пример: cobblestone, oak_log, sand)"));
            return 0;
        }
        Optional<ResourceMapper.Target> mapped = ResourceMapper.map(block, amount);
        if (mapped.isEmpty()) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] «" + id + "» добыть не могу (недобываемый блок)"));
            return 0;
        }
        ResourceMapper.Target t = mapped.get();
        GatherTarget target = new GatherTarget(t.block(), t.item(), t.amount(),
                BuiltInRegistries.ITEM.getKey(t.item()).getPath(), t.note());
        UUID owner = c.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        bot.brain.startJob("collect " + id + " ×" + amount, List.of(target), owner, DEFAULT_RADIUS);
        return 1;
    }

    // ---------- склад ----------

    private static int depositInfo(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        BlockPos pos = bot.brain.getDeposit();
        c.getSource().sendSystemMessage(Component.literal(pos == null
                ? "§7[бот] склад не задан. Задай: deposit here (глядя на сундук) или deposit set <x> <y> <z>. "
                  + "Работает и с большим складом: контейнеры ищутся в радиусе 8 от якоря."
                : "§7[бот] склад: " + pos.toShortString() + " (разгрузиться сейчас: deposit now)"));
        return 1;
    }

    private static int depositHere(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        if (!(c.getSource().getEntity() instanceof ServerPlayer player)) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] «deposit here» может использовать только игрок"));
            return 0;
        }
        HitResult hit = player.pick(DEPOSIT_RAYCAST, 0.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] смотри на сундук/бочку (до " + (int) DEPOSIT_RAYCAST + " блоков)"));
            return 0;
        }
        return setDeposit(c, bot, ((BlockHitResult) hit).getBlockPos());
    }

    private static int depositNow(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        if (bot.brain.getDeposit() == null) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] склад не задан. Сначала: deposit here (глядя на сундук) или deposit set <x> <y> <z>"));
            return 0;
        }
        bot.brain.requestDeposit();
        c.getSource().sendSystemMessage(Component.literal("§7[бот] «" + bot.getGameProfile().name() + "» пошёл разгружаться на склад"));
        return 1;
    }

    private static int depositSet(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        return setDeposit(c, bot, BlockPosArgument.getBlockPos(c, "position"));
    }

    private static int setDeposit(CommandContext<CommandSourceStack> c, GatherBot bot, BlockPos pos) {
        if (!(c.getSource().getLevel().getBlockEntity(pos) instanceof Container)) {
            c.getSource().sendSystemMessage(Component.literal("§c[бот] " + pos.toShortString() + " — не контейнер (нужен сундук/бочка и т.п.)"));
            return 0;
        }
        bot.brain.setDeposit(pos);
        BotPersistence.save(c.getSource().getServer());
        c.getSource().sendSystemMessage(Component.literal("§a[бот] склад для «" + bot.getGameProfile().name() + "»: " + pos.toShortString()
                + " (контейнеры рядом — тоже склад)"));
        return 1;
    }

    private static int depositClear(CommandContext<CommandSourceStack> c) {
        GatherBot bot = getBot(c);
        if (bot == null) return 0;
        bot.brain.clearDeposit();
        BotPersistence.save(c.getSource().getServer());
        c.getSource().sendSystemMessage(Component.literal("§e[бот] склад сброшен — будет копить всё в инвентаре"));
        return 1;
    }

    // ---------- сбор по схематике ----------

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
