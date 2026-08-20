package dev.bifyp.schematicgatherer;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Конечный автомат сбора ресурсов.
 *
 * Очередь целей → для каждой запускаем #mine с нужным количеством →
 * следим за инвентарём → переходим к следующей. Если #mine завершился,
 * а предметов всё ещё не хватает — один повтор, потом позиция уходит в «не удалось».
 */
public final class GatherProcess implements Helper {

    /** Одна цель: что копать (#mine) и по какому предмету считать прогресс. */
    private static final class Task {
        final Block mineBlock;
        final Item countItem;
        final String label;
        final String note;
        int totalNeeded;
        int haveAtStart;
        int quietTicks;
        boolean retried;

        Task(Block mineBlock, Item countItem, int totalNeeded, String label, String note) {
            this.mineBlock = mineBlock;
            this.countItem = countItem;
            this.totalNeeded = totalNeeded;
            this.label = label;
            this.note = note;
        }
    }

    private record Parsed(String name, List<Task> tasks, List<String> lines, List<String> skipped, int totalItems) {}

    private final Deque<Task> queue = new ArrayDeque<>();
    private final List<String> failed = new ArrayList<>();
    private Task current;
    private boolean running;
    private String schematicName = "";

    // ---------- команды ----------

    /** Показать список материалов, ничего не добывая. */
    public void printMaterials(File file) {
        Parsed parsed;
        try {
            parsed = parse(file);
        } catch (Exception e) {
            logDirect("Не удалось прочитать схематику: " + e.getMessage(), ChatFormatting.RED);
            return;
        }
        logDirect("Материалы для «" + parsed.name() + "» (позиций: " + parsed.tasks().size() + ", предметов ~" + parsed.totalItems() + "):");
        parsed.lines().stream().limit(25).forEach(this::logDirect);
        if (parsed.lines().size() > 25) {
            logDirect("…и ещё " + (parsed.lines().size() - 25) + " позиций");
        }
        printSkipped(parsed.skipped());
    }

    /** Начать сбор. */
    public void start(File file) {
        Parsed parsed;
        try {
            parsed = parse(file);
        } catch (Exception e) {
            logDirect("Не удалось прочитать схематику: " + e.getMessage(), ChatFormatting.RED);
            return;
        }
        queue.clear();
        failed.clear();
        current = null;
        schematicName = parsed.name();

        logDirect("Схематика «" + schematicName + "»: позиций " + parsed.tasks().size() + ", предметов ~" + parsed.totalItems());
        parsed.lines().stream().limit(15).forEach(this::logDirect);
        if (parsed.lines().size() > 15) {
            logDirect("…и ещё " + (parsed.lines().size() - 15) + " (полный список: #gather list " + schematicName + ")");
        }
        printSkipped(parsed.skipped());

        if (parsed.tasks().isEmpty()) {
            logDirect("Добывать нечего — всё уже есть или схема пустая.");
            return;
        }
        queue.addAll(parsed.tasks());
        running = true;
        logDirect("Погнали! Остановка: #gather stop");
    }

    public void cancel() {
        if (!running) {
            logDirect("Сбор не запущен.");
            return;
        }
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone != null) {
            baritone.getMineProcess().cancel();
        }
        running = false;
        current = null;
        queue.clear();
        logDirect("Сбор остановлен.");
    }

    public void printStatus() {
        if (!running) {
            logDirect("Сбор не запущен. Старт: #gather <файл>");
            return;
        }
        if (current != null) {
            logDirect("Сбор «" + schematicName + "»: " + current.label + " — "
                    + count(current.countItem) + "/" + current.totalNeeded
                    + ", в очереди ещё " + queue.size());
        } else {
            logDirect("Сбор «" + schematicName + "»: между задачами, в очереди " + queue.size());
        }
    }

    // ---------- тик ----------

    public void tick() {
        if (!running) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) return;

        if (current == null) {
            if (queue.isEmpty()) {
                finish();
                return;
            }
            beginNext(baritone);
            return;
        }

        int have = count(current.countItem);
        if (have >= current.totalNeeded) {
            logDirect("✔ " + current.label + " — есть " + have);
            current = null;
            return;
        }

        if (baritone.getMineProcess().isActive()) {
            current.quietTicks = 0;
            return;
        }

        // #mine завершился (или не нашёл блоки), а предметов всё ещё мало
        current.quietTicks++;
        if (current.quietTicks == 20 && !current.retried) {
            current.retried = true;
            int remaining = current.totalNeeded - have;
            logDirect("…повторная попытка: " + current.label + " (осталось " + remaining + ")");
            BaritoneAPI.getProvider().getWorldScanner().repack(baritone.getPlayerContext());
            baritone.getMineProcess().mine(remaining, current.mineBlock);
        } else if (current.quietTicks > 60) {
            failed.add(current.label + " — добыто " + have + " из " + current.totalNeeded);
            logDirect("✖ Не получилось: " + current.label + ". Пропускаю.", ChatFormatting.RED);
            current = null;
        }
    }

    private void beginNext(IBaritone baritone) {
        current = queue.poll();
        current.haveAtStart = count(current.countItem);
        int remaining = current.totalNeeded - current.haveAtStart;
        if (remaining <= 0) {
            current = null; // уже есть в инвентаре
            return;
        }
        logDirect("⛏ " + current.label + ": добываю " + remaining
                + (current.note.isEmpty() ? "" : " (" + current.note + ")"));
        BaritoneAPI.getProvider().getWorldScanner().repack(baritone.getPlayerContext());
        baritone.getMineProcess().mine(remaining, current.mineBlock);
    }

    private void finish() {
        running = false;
        current = null;
        logDirect("✔ Сбор для «" + schematicName + "» завершён!");
        if (!failed.isEmpty()) {
            logDirect("Не удалось добыть:", ChatFormatting.RED);
            failed.forEach(f -> logDirect(" - " + f, ChatFormatting.RED));
        }
    }

    // ---------- разбор схематики ----------

    private Parsed parse(File file) throws IOException {
        Optional<ISchematicFormat> format = BaritoneAPI.getProvider().getSchematicSystem().getByFile(file);
        if (format.isEmpty()) {
            throw new IOException("неподдерживаемый формат файла");
        }
        IStaticSchematic schem;
        try (InputStream in = new FileInputStream(file)) {
            schem = format.get().parse(in);
        }
        schem.reset();

        MaterialCounter.Result res = MaterialCounter.count(schem);

        // «блок из схематики» → «что реально копать», дубликаты сливаем
        Map<String, Task> merged = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>(res.skippedLines());
        for (Map.Entry<Block, Integer> e : res.materials().entrySet()) {
            Optional<ResourceMapper.Target> target = ResourceMapper.map(e.getKey(), e.getValue());
            if (target.isEmpty()) {
                skipped.add(blockName(e.getKey()) + " ×" + e.getValue() + " — недобываемо");
                continue;
            }
            ResourceMapper.Target tg = target.get();
            String key = blockName(tg.block()) + "->" + itemName(tg.item());
            Task task = merged.get(key);
            if (task == null) {
                merged.put(key, new Task(tg.block(), tg.item(), tg.amount(), itemName(tg.item()), tg.note()));
            } else {
                task.totalNeeded += tg.amount();
            }
        }

        List<Task> tasks = new ArrayList<>(merged.values());
        tasks.sort((a, b) -> Integer.compare(b.totalNeeded, a.totalNeeded));

        List<String> lines = new ArrayList<>();
        int totalItems = 0;
        for (Task task : tasks) {
            int have = count(task.countItem);
            totalItems += task.totalNeeded;
            StringBuilder sb = new StringBuilder("- ").append(task.label).append(" ×").append(task.totalNeeded);
            if (have > 0) sb.append(" (есть ").append(have).append(")");
            if (!task.note.isEmpty()) sb.append(" — ").append(task.note);
            lines.add(sb.toString());
        }
        return new Parsed(file.getName(), tasks, lines, skipped, totalItems);
    }

    private void printSkipped(List<String> skipped) {
        if (skipped.isEmpty()) return;
        logDirect("Пропущено:", ChatFormatting.YELLOW);
        skipped.forEach(s -> logDirect(" - " + s, ChatFormatting.YELLOW));
    }

    // ---------- утилиты ----------

    private static int count(Item item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        int total = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static String blockName(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getPath();
    }

    private static String itemName(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
