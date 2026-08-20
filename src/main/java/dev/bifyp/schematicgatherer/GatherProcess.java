package dev.bifyp.schematicgatherer;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.schematic.IStaticSchematic;
import baritone.api.schematic.format.ISchematicFormat;
import baritone.api.utils.Helper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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
 *
 * Склад (depositPos, задаётся «#gather deposit ...»). Разгрузка случается:
 *  - когда инвентарь полностью заполнен во время сбора;
 *  - после завершения сбора (если есть что нести);
 *  - по команде «#gather deposit now» — в любой момент, даже без задачи.
 *
 * Дорогу к складу строит Baritone (GoalGetToBlock): при необходимости он сам
 * прокапывается через блоки (allowBreak по умолчанию включён). Разгрузка —
 * правый клик по контейнеру и QUICK_MOVE по слотам; инструмент в активном
 * слоте не трогаем. После разгрузки сбор продолжается с текущей цели.
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

    private enum DepositStage { IDLE, WALKING, OPENING, DUMPING }

    private static final double CHEST_REACH = 4.0;
    /** Сколько тиков ждём путь до склада, прежде чем сдаться. */
    private static final int WALK_TIMEOUT = 1200;
    private static final int MAX_RETRIES = 2;
    /** Ждём открытия контейнера после правого клика. */
    private static final int OPEN_TIMEOUT = 20;
    private static final int DUMP_PER_TICK = 9;

    private final Deque<Task> queue = new ArrayDeque<>();
    private final List<String> failed = new ArrayList<>();
    private Task current;
    private boolean running;
    private String schematicName = "";

    // склад
    private BlockPos depositPos;
    private DepositStage depositStage = DepositStage.IDLE;
    private boolean depositRequested;
    private boolean chestUnreachable;
    private boolean warnedFull;
    private int walkTicks;
    private int pathRetries;
    private int openTicks;
    private int openRetries;
    private int dumpStuckTicks;

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
        warnedFull = false;
        chestUnreachable = false;

        logDirect("Схематика «" + schematicName + "»: позиций " + parsed.tasks().size() + ", предметов ~" + parsed.totalItems()
                + (depositPos == null ? "" : ", склад: " + depositPos.toShortString()));
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
        Minecraft mc = Minecraft.getInstance();
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        boolean wasDepositing = depositStage != DepositStage.IDLE || depositRequested;
        if (wasDepositing) {
            depositStage = DepositStage.IDLE;
            depositRequested = false;
            if (baritone != null) {
                baritone.getCustomGoalProcess().onLostControl();
            }
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
        }
        if (!running) {
            logDirect(wasDepositing ? "Разгрузка остановлена." : "Сбор не запущен.");
            return;
        }
        if (baritone != null) {
            baritone.getMineProcess().cancel();
        }
        running = false;
        current = null;
        queue.clear();
        logDirect("Сбор остановлен.");
    }

    public void printStatus() {
        if (depositStage != DepositStage.IDLE) {
            logDirect("Иду разгружаться на склад " + (depositPos == null ? "?" : depositPos.toShortString()));
            return;
        }
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

    // ---------- склад ----------

    public BlockPos getDeposit() {
        return depositPos;
    }

    public void setDeposit(BlockPos pos) {
        this.depositPos = pos;
        this.chestUnreachable = false;
        this.warnedFull = false;
    }

    public void clearDeposit() {
        this.depositPos = null;
        this.chestUnreachable = false;
    }

    /** «#gather deposit now» — разгрузиться прямо сейчас, даже без активного сбора. */
    public boolean requestDeposit() {
        if (depositPos == null) {
            logDirect("Склад не задан. Сначала: #gather deposit here (глядя на сундук)", ChatFormatting.RED);
            return false;
        }
        chestUnreachable = false;
        depositRequested = true;
        return true;
    }

    // ---------- тик ----------

    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) return;

        // ручная разгрузка — работает и без активного сбора
        if (depositRequested && depositStage == DepositStage.IDLE) {
            depositRequested = false;
            if (depositPos != null) {
                logDirect("Иду разгружаться на склад " + depositPos.toShortString() + "…");
                startDeposit(baritone);
            }
        }
        if (depositStage != DepositStage.IDLE) {
            depositTick(mc, baritone);
            return;
        }

        if (!running) return;

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

        // инвентарь полон — едем разгружаться, потом продолжим с этой же цели
        if (isInventoryFull(mc)) {
            if (depositPos != null && !chestUnreachable) {
                logDirect("Инвентарь полон — иду разгружаться на склад " + depositPos.toShortString() + "…");
                startDeposit(baritone);
                return;
            }
            if (depositPos == null && !warnedFull) {
                warnedFull = true;
                logDirect("Инвентарь полон, склад не задан — дропы останутся лежать. "
                        + "Задай: #gather deposit here (глядя на сундук)", ChatFormatting.YELLOW);
            }
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
        Minecraft mc = Minecraft.getInstance();
        boolean goDeposit = depositPos != null && !chestUnreachable
                && mc.player != null && hasAnythingToDeposit(mc);
        logDirect("✔ Сбор для «" + schematicName + "» завершён!" + (goDeposit ? " Несу добытое на склад…" : ""));
        if (!failed.isEmpty()) {
            logDirect("Не удалось добыть:", ChatFormatting.RED);
            failed.forEach(f -> logDirect(" - " + f, ChatFormatting.RED));
        }
        if (goDeposit) {
            depositRequested = true;
        }
    }

    // ---------- склад: путь, открытие, разгрузка ----------

    private void startDeposit(IBaritone baritone) {
        baritone.getMineProcess().cancel();
        depositStage = DepositStage.WALKING;
        walkTicks = 0;
        pathRetries = 0;
        dumpStuckTicks = 0;
        // Baritone строит путь с учётом ломания блоков — из шахты прокопается сам
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(depositPos));
    }

    private void depositTick(Minecraft mc, IBaritone baritone) {
        switch (depositStage) {
            case WALKING -> {
                if (mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(depositPos)) <= CHEST_REACH) {
                    depositStage = DepositStage.OPENING;
                    openTicks = 0;
                    openRetries = 0;
                    rightClickChest(mc);
                    return;
                }
                if (++walkTicks > WALK_TIMEOUT) {
                    failDeposit(mc, baritone, "не дошёл до склада за минуту");
                    return;
                }
                if (!baritone.getCustomGoalProcess().isActive()) {
                    if (++pathRetries > MAX_RETRIES) {
                        failDeposit(mc, baritone, "Baritone не смог проложить путь к складу");
                        return;
                    }
                    baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(depositPos));
                }
            }
            case OPENING -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    depositStage = DepositStage.DUMPING;
                    dumpStuckTicks = 0;
                    return;
                }
                if (++openTicks > OPEN_TIMEOUT) {
                    if (++openRetries > MAX_RETRIES) {
                        failDeposit(mc, baritone, "контейнер не открывается");
                        return;
                    }
                    openTicks = 0;
                    rightClickChest(mc);
                }
            }
            case DUMPING -> {
                if (mc.player.containerMenu == mc.player.inventoryMenu) {
                    // экран закрылся (в т.ч. вручную) — считаем разгрузку законченной
                    endDeposit(mc, baritone, "✔ Разгрузился на складе");
                    return;
                }
                int moved = dumpSome(mc);
                if (!hasMovableItems(mc)) {
                    endDeposit(mc, baritone, "✔ Разгрузился на складе");
                } else if (moved == 0 && ++dumpStuckTicks > 20) {
                    endDeposit(mc, baritone, "⚠ Склад полон — часть осталась при мне");
                } else if (moved > 0) {
                    dumpStuckTicks = 0;
                }
            }
            default -> {}
        }
    }

    private void rightClickChest(Minecraft mc) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(depositPos), Direction.UP, depositPos, false));
    }

    /**
     * Shift-кликаем предметы в открытый контейнер. В стандартных меню (сундук,
     * бочка, шалкер, воронка) слоты игрока — всегда последние 36, хотбар — последние 9.
     * Инструмент в активном слоте не трогаем, чтобы копать было чем.
     *
     * В 26.1 старый ClickType/handleInventoryMouseClick переименован в
     * ContainerInput/handleContainerInput (см. litematica LTS/26.1).
     */
    private int dumpSome(Minecraft mc) {
        var menu = mc.player.containerMenu;
        int total = menu.slots.size();
        if (total < 36) return 0;
        int heldMenuSlot = total - 9 + mc.player.getInventory().getSelectedSlot();
        int moved = 0;
        for (int i = total - 36; i < total && moved < DUMP_PER_TICK; i++) {
            if (i == heldMenuSlot) continue;
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem()) continue;
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            moved++;
        }
        return moved;
    }

    private boolean hasMovableItems(Minecraft mc) {
        var menu = mc.player.containerMenu;
        int total = menu.slots.size();
        if (total < 36) return false;
        int heldMenuSlot = total - 9 + mc.player.getInventory().getSelectedSlot();
        for (int i = total - 36; i < total; i++) {
            if (i == heldMenuSlot) continue;
            if (menu.slots.get(i).hasItem()) return true;
        }
        return false;
    }

    private void endDeposit(Minecraft mc, IBaritone baritone, String message) {
        logDirect(message);
        cleanupDeposit(mc, baritone);
        resumeGather(baritone);
    }

    private void failDeposit(Minecraft mc, IBaritone baritone, String reason) {
        logDirect("✖ Разгрузка не удалась: " + reason
                + ". Склад отключён до перезадачи (#gather deposit here/set).", ChatFormatting.RED);
        chestUnreachable = true;
        cleanupDeposit(mc, baritone);
        resumeGather(baritone);
    }

    private void cleanupDeposit(Minecraft mc, IBaritone baritone) {
        depositStage = DepositStage.IDLE;
        depositRequested = false;
        baritone.getCustomGoalProcess().onLostControl();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
    }

    /** После разгрузки продолжаем прерванную цель. */
    private void resumeGather(IBaritone baritone) {
        if (!running || current == null) return;
        int remaining = current.totalNeeded - count(current.countItem);
        if (remaining <= 0) return; // следующий тик сам закроет цель
        current.quietTicks = 0;
        logDirect("Продолжаю: " + current.label + " (осталось " + remaining + ")");
        BaritoneAPI.getProvider().getWorldScanner().repack(baritone.getPlayerContext());
        baritone.getMineProcess().mine(remaining, current.mineBlock);
    }

    private static boolean isInventoryFull(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private static boolean hasAnythingToDeposit(Minecraft mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == inv.getSelectedSlot()) continue;
            if (!inv.getItem(i).isEmpty()) return true;
        }
        return false;
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
