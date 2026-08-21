package dev.bifyp.schematicgatherer.bot;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Серверный «мозг» бота: очередь «что добыть» → поиск ближайшего блока →
 * подойти → сломать (прогресс разрушения как у Carpet action pack) → пока не хватит.
 *
 * Движение без A* (прямая + прыжок через одиночный блок), с «мёртвыми зонами»
 * вокруг мест застревания и без спама в чат.
 *
 * Склад (depositAnchor, задаётся /gatherbot <имя> deposit ...) — двусторонний:
 *  - РАЗГРУЗКА: инвентарь полон / конец задачи / «deposit now» → телепорт на
 *    склад, переложить всё (кроме инструмента в руке), телепорт обратно.
 *    Контейнер полон/пропал — следующий в радиусе 8 от якоря (большой склад).
 *  - ЗАБОР: в начале задачи бот телепортируется на склад и забирает то, что там
 *    уже лежит по плану — добывает только остаток;
 *  - ИНСТРУМЕНТ: кирка сломалась/не выдали — один раз на цель телепортируется
 *    за подходящим инструментом на склад и возвращается.
 *
 * pause/resume: пауза замораживает задачу с сохранением прогресса.
 * skip: «skip» — пропустить текущую цель, «skip <id>» — вычеркнуть ресурс из плана.
 */
public final class BotBrain {

    private static final double REACH = 4.5;
    private static final double CHEST_REACH = 3.0;
    private static final int SCAN_INTERVAL = 20;
    private static final int STUCK_TICKS = 60;
    private static final int SCAN_VERTICAL = 12;
    /** Радиус кластера вокруг точки застревания, который считаем недоступным целиком. */
    private static final int DEAD_ZONE_RADIUS = 8;
    /** Сколько мёртвых зон по одной цели терпим, прежде чем сдаться. */
    private static final int MAX_DEAD_ZONES = 60;
    /** Радиус поиска контейнеров склада вокруг якоря. */
    private static final int WAREHOUSE_RADIUS = 8;
    private static final int WAREHOUSE_VERTICAL = 3;

    private final GatherBot bot;
    private final Deque<GatherTarget> queue = new ArrayDeque<>();
    private final List<String> failed = new ArrayList<>();
    private final List<BlockPos> deadZones = new ArrayList<>();

    private GatherTarget current;
    private BlockPos targetPos;
    private BlockPos miningPos;
    private float miningProgress;
    private int scanCooldown;
    private int stuckTicks;
    private BlockPos lastPos;
    private int skippedUnreachable;
    private UUID owner;
    private String jobName = "";
    private int radius = 48;
    private boolean paused;

    // склад (задаётся командой, живёт пока бот заспавнен; переживает рестарт)
    private BlockPos depositAnchor;   // центр склада — то, что задали командой
    private BlockPos depositPos;      // контейнер, с которым работаем прямо сейчас
    private final Set<BlockPos> triedChests = new HashSet<>();
    private BlockPos depositReturnPos; // откуда телепортнулись на склад — сюда возвращаемся
    private boolean depositRequested;
    private boolean chestUnreachable;
    private boolean warnedFull;
    private boolean needWithdraw;      // забрать со склада перед началом добычи
    private boolean toolFetchTried;    // за инструментом на склад сходили (один раз на цель)

    public BotBrain(GatherBot bot) {
        this.bot = bot;
    }

    public boolean isRunning() {
        return current != null || !queue.isEmpty();
    }

    public boolean isPaused() {
        return paused;
    }

    /** Пауза замораживает задачу, прогресс (очередь, текущая цель) сохраняется. */
    public void setPaused(boolean value) {
        paused = value;
        if (value) {
            abortMining();
            bot.zza = 0;
            bot.xxa = 0;
            bot.setSprinting(false);
        }
    }

    public void startJob(String name, List<GatherTarget> targets, UUID owner, int radius) {
        stopJob(false);
        queue.addAll(targets);
        failed.clear();
        this.owner = owner;
        this.jobName = name;
        this.radius = radius;
        chestUnreachable = false;
        warnedFull = false;
        triedChests.clear();
        depositPos = depositAnchor;
        needWithdraw = depositAnchor != null; // сначала забираем со склада, что там уже есть
        tell("§a[бот] задача «" + name + "»: позиций " + targets.size() + ", радиус поиска " + radius
                + (depositAnchor == null ? "" : ", склад: " + depositAnchor.toShortString()));
    }

    public void stopJob(boolean report) {
        abortMining();
        bot.zza = 0;
        bot.xxa = 0;
        bot.setSprinting(false);
        queue.clear();
        current = null;
        targetPos = null;
        deadZones.clear();
        depositRequested = false;
        depositReturnPos = null;
        needWithdraw = false;
        if (report) tell("§e[бот] задача остановлена.");
    }

    public String status() {
        if (paused) return "на паузе («" + jobName + "», в очереди " + queue.size() + "). Продолжить: resume";
        if (!isRunning()) return "нет активной задачи";
        if (current == null) return "«" + jobName + "»: между задачами, в очереди " + queue.size();
        return "«" + jobName + "»: " + current.label() + " " + countItem(current.item()) + "/" + current.needed()
                + ", в очереди " + queue.size();
    }

    // ---------- skip ----------

    /** Пропустить текущую цель. @return false, если активной цели нет. */
    public boolean skipCurrent() {
        if (current == null) return false;
        tell("§e[бот] пропускаю: " + current.label());
        nextTarget();
        return true;
    }

    /** Вычеркнуть из плана все цели с таким предметом/блоком. @return сколько позиций убрано. */
    public int skipById(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        int removed = 0;
        var it = queue.iterator();
        while (it.hasNext()) {
            GatherTarget t = it.next();
            if (matchesId(t, path)) {
                it.remove();
                removed++;
            }
        }
        if (current != null && matchesId(current, path)) {
            tell("§e[бот] пропускаю текущую цель: " + current.label());
            nextTarget();
            removed++;
        }
        return removed;
    }

    private static boolean matchesId(GatherTarget t, String path) {
        return t.label().equals(path)
                || BuiltInRegistries.ITEM.getKey(t.item()).getPath().equals(path)
                || BuiltInRegistries.BLOCK.getKey(t.block()).getPath().equals(path);
    }

    // ---------- склад ----------

    public void setDeposit(BlockPos pos) {
        this.depositAnchor = pos;
        this.depositPos = pos;
        this.triedChests.clear();
        this.chestUnreachable = false;
        this.warnedFull = false;
    }

    public void clearDeposit() {
        this.depositAnchor = null;
        this.depositPos = null;
        this.chestUnreachable = false;
    }

    /** Якорь склада (центр, заданный командой). */
    public BlockPos getDeposit() {
        return depositAnchor;
    }

    /** Ручная команда «deposit now»: телепорт на склад, разгрузка, телепорт обратно. */
    public void requestDeposit() {
        chestUnreachable = false; // после ручной команды даём складу второй шанс
        triedChests.clear();
        depositPos = depositAnchor;
        depositReturnPos = bot.blockPosition();
        depositRequested = true;
    }

    // ---------- тик ----------

    public void tick() {
        if (bot.isRemoved()) {
            stopJob(false);
            return;
        }
        if (paused) return;

        // разгрузка по команде / после задачи — работает и без активной задачи
        if (depositRequested) {
            abortMining();
            targetPos = null;
            if (depositReturnPos == null) depositReturnPos = bot.blockPosition();
            if (depositAnchor == null || chestUnreachable || depositTick()) {
                depositRequested = false;
            }
            return;
        }

        // забор со склада перед стартом добычи (один раз на задачу)
        if (needWithdraw) {
            needWithdraw = false;
            withdrawFromWarehouse();
        }

        if (current == null) {
            if (queue.isEmpty()) return;
            current = queue.poll();
            tell("§7[бот] цель: " + current.label() + " ×" + current.needed()
                    + (current.note().isEmpty() ? "" : " (" + current.note() + ")"));
            targetPos = null;
            deadZones.clear();
            skippedUnreachable = 0;
            stuckTicks = 0;
            scanCooldown = 0;
            toolFetchTried = false;
            lastPos = bot.blockPosition();
        }

        int have = countItem(current.item());
        if (have >= current.needed()) {
            tell("§a[бот] ✔ " + current.label() + " — есть " + have + unreachableSuffix());
            nextTarget();
            return;
        }

        // инвентарь полон -> телепорт на склад, разгрузка, телепорт обратно
        if (isInventoryFull()) {
            if (depositAnchor == null || chestUnreachable) {
                if (!warnedFull) {
                    warnedFull = true;
                    tell(depositAnchor == null
                            ? "§e[бот] инвентарь полон, склад не задан — дропы останутся лежать. "
                              + "Задай: /gatherbot " + bot.getGameProfile().name() + " deposit here (глядя на сундук)"
                            : "§e[бот] инвентарь полон, а склад забит — дропы останутся лежать.");
                }
            } else {
                abortMining();
                targetPos = null;
                if (depositReturnPos == null) depositReturnPos = bot.blockPosition();
                depositTick();
                return;
            }
        }

        boolean needRescan = targetPos == null || !level().getBlockState(targetPos).is(current.block());
        if (needRescan) {
            abortMining();
            targetPos = null;
            if (--scanCooldown <= 0) {
                scanCooldown = SCAN_INTERVAL;
                targetPos = findNearest(current.block());
                if (targetPos == null) {
                    giveUpCurrent();
                    return;
                }
                stuckTicks = 0;
                lastPos = bot.blockPosition();
            }
            if (targetPos == null) return; // ждём следующего скана
        }

        double dist = bot.getEyePosition().distanceTo(Vec3.atCenterOf(targetPos));
        if (dist > REACH) {
            walkTo(targetPos);
        } else {
            bot.zza = 0;
            bot.setSprinting(false);
            fetchToolIfNeeded(level().getBlockState(targetPos));
            mine(targetPos);
        }
    }

    /** Цель провалена: либо блоков нет вовсе, либо всё найденное оказалось недоступным. */
    private void giveUpCurrent() {
        String reason = deadZones.isEmpty()
                ? "не найден в радиусе " + radius
                : "все найденные участки недоступны (мёртвых зон: " + deadZones.size() + ")";
        failed.add(current.label() + " — " + reason);
        tell("§c[бот] ✖ " + current.label() + ": " + reason + ". Пропускаю.");
        nextTarget();
    }

    private void nextTarget() {
        abortMining();
        current = null;
        targetPos = null;
        if (queue.isEmpty()) finish();
    }

    private void walkTo(BlockPos pos) {
        abortMining();
        bot.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        bot.zza = 1.0F;
        bot.setSprinting(true);
        if (bot.horizontalCollision && bot.onGround()) {
            bot.jumpFromGround();
        }
        // застревание: не сдвинулся за STUCK_TICKS — точка и весь участок вокруг неё недостижимы
        if (++stuckTicks >= STUCK_TICKS) {
            if (lastPos != null && bot.blockPosition().distSqr(lastPos) < 1.0) {
                markUnreachable(pos);
                targetPos = null;
                scanCooldown = 1;
            }
            stuckTicks = 0;
            lastPos = bot.blockPosition();
        }
    }

    /**
     * Метит кластер вокруг точки недоступным. В чат — только первая неудача по цели,
     * дальше тихо (сводка будет в unreachableSuffix при завершении цели).
     */
    private void markUnreachable(BlockPos pos) {
        deadZones.add(pos);
        skippedUnreachable++;
        if (skippedUnreachable == 1) {
            tell("§e[бот] не могу подойти к " + pos.toShortString()
                    + " — вычёркиваю участок и ищу другой " + current.label());
        }
        if (deadZones.size() >= MAX_DEAD_ZONES) {
            giveUpCurrent();
        }
    }

    private String unreachableSuffix() {
        return skippedUnreachable == 0 ? "" : " (недоступных блоков пропущено: " + skippedUnreachable + ")";
    }

    // ---------- склад: забор и инструменты ----------

    /** Перед добычей забираем со склада то, что там уже лежит по плану (добыча — только остаток). */
    private void withdrawFromWarehouse() {
        if (depositAnchor == null) return;
        Map<Item, Integer> need = new HashMap<>();
        if (current != null) need.merge(current.item(), current.needed(), Integer::sum);
        for (GatherTarget t : queue) need.merge(t.item(), t.needed(), Integer::sum);
        need.replaceAll((item, n) -> Math.max(0, n - countItem(item)));
        need.values().removeIf(n -> n <= 0);
        if (need.isEmpty()) return;
        List<BlockPos> chests = findAllChests();
        if (chests.isEmpty()) return;

        BlockPos back = bot.blockPosition();
        teleportNear(depositAnchor);
        int took = 0;
        for (BlockPos chestPos : chests) {
            if (!(level().getBlockEntity(chestPos) instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                Integer remaining = need.get(stack.getItem());
                if (remaining == null || remaining <= 0) continue;
                int take = Math.min(stack.getCount(), remaining);
                ItemStack taken = container.removeItem(i, take);
                int before = taken.getCount();
                bot.getInventory().addItem(taken);
                int used = before - taken.getCount();
                if (taken.getCount() > 0) container.setItem(i, taken); // не влезло — вернуть
                if (used > 0) {
                    need.merge(stack.getItem(), -used, Integer::sum);
                    took += used;
                }
                container.setChanged();
            }
        }
        teleportBack(back);
        if (took > 0) tell("§7[бот] забрал со склада " + took + " предм. — добуду только остаток");
    }

    /** Кирка сломалась/не выдали — берём подходящий инструмент со склада (один раз на цель). */
    private void fetchToolIfNeeded(BlockState state) {
        if (toolFetchTried || depositAnchor == null) return;
        Inventory inv = bot.getInventory();
        float best = 1.0F;
        for (int i = 0; i < 9; i++) {
            best = Math.max(best, inv.getItem(i).getDestroySpeed(state));
        }
        if (best > 1.0F) return; // инструмент в хотбаре есть
        toolFetchTried = true;
        List<BlockPos> chests = findAllChests();
        if (chests.isEmpty()) return;

        BlockPos back = bot.blockPosition();
        teleportNear(depositAnchor);
        boolean found = false;
        outer:
        for (BlockPos chestPos : chests) {
            if (!(level().getBlockEntity(chestPos) instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || stack.getDestroySpeed(state) <= 1.0F) continue;
                ItemStack taken = container.removeItem(i, 1);
                int before = taken.getCount();
                bot.getInventory().addItem(taken);
                if (taken.getCount() > 0) container.setItem(i, taken); // не влезло — вернуть
                if (taken.getCount() < before) {
                    container.setChanged();
                    found = true;
                }
                break outer;
            }
        }
        teleportBack(back);
        if (found) tell("§7[бот] взял инструмент со склада");
    }

    // ---------- склад: телепорт туда, разгрузка, телепорт обратно ----------

    private enum DepositResult { DONE, NEXT_CHEST, FAILED }

    /** @return true — разгрузка завершена (успешно, склад полон или отказ). */
    private boolean depositTick() {
        double dist = bot.getEyePosition().distanceTo(Vec3.atCenterOf(depositPos));
        if (dist > CHEST_REACH) {
            teleportNear(depositPos);
            return false;
        }
        bot.zza = 0;
        bot.setSprinting(false);
        DepositResult res = depositItems();
        if (res == DepositResult.NEXT_CHEST) return false; // на след. тике телепорт к следующему контейнеру
        returnHome();
        return true;
    }

    private void teleportNear(BlockPos chest) {
        BlockPos spot = findTeleportSpot(chest);
        bot.teleportTo(level(), spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                Set.of(), bot.getYRot(), bot.getXRot(), true);
    }

    private void teleportBack(BlockPos back) {
        bot.teleportTo(level(), back.getX() + 0.5, back.getY(), back.getZ() + 0.5,
                Set.of(), bot.getYRot(), bot.getXRot(), true);
    }

    /** Соседняя клетка с полом и двумя воздухами; запасной вариант — встать на контейнер. */
    private BlockPos findTeleportSpot(BlockPos chest) {
        ServerLevel level = level();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = chest.relative(dir);
            if (isStandable(level, candidate)) return candidate;
        }
        return chest.above();
    }

    private static boolean isStandable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir()
                && level.getBlockState(pos.above()).isAir()
                && !level.getBlockState(pos.below()).isAir()
                && level.getFluidState(pos).isEmpty();
    }

    /** Возврат туда, откуда телепортнулись на склад. */
    private void returnHome() {
        if (depositReturnPos != null) {
            teleportBack(depositReturnPos);
            depositReturnPos = null;
        }
    }

    /**
     * Разгрузка в контейнер под рукой. Контейнер полон или пропал — переключаемся
     * на следующий контейнер склада (большой склад = много сундуков вокруг якоря).
     */
    private DepositResult depositItems() {
        if (!(level().getBlockEntity(depositPos) instanceof Container container)) {
            triedChests.add(depositPos);
            if (switchToNextChest()) return DepositResult.NEXT_CHEST;
            chestUnreachable = true;
            tell("§c[бот] на складе не осталось контейнеров — разгрузка отключена.");
            return DepositResult.FAILED;
        }
        int moved = moveAllInto(container);
        if (!hasAnythingToDeposit()) {
            tell(moved > 0
                    ? "§7[бот] разгрузился на склад (предметов: " + moved + ")"
                    : "§7[бот] нечего складывать");
            return DepositResult.DONE;
        }
        // контейнер полон — следующий
        triedChests.add(depositPos);
        if (switchToNextChest()) {
            tell("§7[бот] контейнер полон — следующий на складе…");
            return DepositResult.NEXT_CHEST;
        }
        chestUnreachable = true;
        tell(moved > 0
                ? "§c[бот] склад заполнен — влезло только " + moved + ". Разгрузка отключена."
                : "§c[бот] склад заполнен — разгрузка отключена.");
        return DepositResult.FAILED;
    }

    /** Перекладывает в контейнер всё, кроме предмета в активном слоте (инструмента). */
    private int moveAllInto(Container container) {
        Inventory inv = bot.getInventory();
        int moved = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == inv.getSelectedSlot()) continue; // инструмент остаётся в руке
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack remainder = HopperBlockEntity.addItem(null, container, stack.copy(), null);
            int left = remainder.isEmpty() ? 0 : remainder.getCount();
            moved += stack.getCount() - left;
            inv.setItem(i, left == 0 ? ItemStack.EMPTY : remainder);
        }
        container.setChanged();
        return moved;
    }

    private boolean switchToNextChest() {
        BlockPos next = findNextChest();
        if (next == null) return false;
        depositPos = next;
        return true;
    }

    /** Следующий неиспользованный контейнер склада (по близости к якорю). */
    private BlockPos findNextChest() {
        for (BlockPos p : findAllChests()) {
            if (!triedChests.contains(p)) return p;
        }
        return null;
    }

    /** Все контейнеры склада вокруг якоря, ближайшие первыми. */
    private List<BlockPos> findAllChests() {
        List<BlockPos> out = new ArrayList<>();
        if (depositAnchor == null) return out;
        ServerLevel level = level();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -WAREHOUSE_VERTICAL; dy <= WAREHOUSE_VERTICAL; dy++) {
            for (int dx = -WAREHOUSE_RADIUS; dx <= WAREHOUSE_RADIUS; dx++) {
                for (int dz = -WAREHOUSE_RADIUS; dz <= WAREHOUSE_RADIUS; dz++) {
                    pos.set(depositAnchor.getX() + dx, depositAnchor.getY() + dy, depositAnchor.getZ() + dz);
                    if (!level.hasChunkAt(pos)) continue;
                    if (!(level.getBlockEntity(pos) instanceof Container)) continue;
                    out.add(pos.immutable());
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(depositAnchor)));
        return out;
    }

    private boolean isInventoryFull() {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private boolean hasAnythingToDeposit() {
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == inv.getSelectedSlot()) continue;
            if (!inv.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    /** Ломание блока — порт ActionType.ATTACK из Carpet (START/STOP_DESTROY_BLOCK + прогресс). */
    private void mine(BlockPos pos) {
        selectBestTool(level().getBlockState(pos));
        if (miningPos == null || !miningPos.equals(pos)) {
            abortMining();
            bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    Direction.UP, level().getMaxY(), -1);
            miningPos = pos;
            miningProgress = 0;
        }
        BlockState state = level().getBlockState(pos);
        miningProgress += state.getDestroyProgress(bot, level(), pos);
        bot.swing(InteractionHand.MAIN_HAND);
        if (miningProgress >= 1.0F) {
            bot.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    Direction.UP, level().getMaxY(), -1);
            miningPos = null;
            miningProgress = 0;
        } else {
            level().destroyBlockProgress(bot.getId(), pos, (int) (miningProgress * 10));
        }
    }

    private void abortMining() {
        if (miningPos == null) return;
        bot.gameMode.handleBlockBreakAction(miningPos, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                Direction.UP, level().getMaxY(), -1);
        level().destroyBlockProgress(bot.getId(), miningPos, -1);
        miningPos = null;
        miningProgress = 0;
    }

    private BlockPos findNearest(Block block) {
        ServerLevel level = level();
        BlockPos center = bot.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -SCAN_VERTICAL; dy <= SCAN_VERTICAL; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.hasChunkAt(pos)) continue;
                    if (!level.getBlockState(pos).is(block)) continue;
                    if (inDeadZone(pos)) continue;
                    if (!isExposed(level, pos)) continue;
                    double d = pos.distSqr(center);
                    if (d < bestDist) {
                        bestDist = d;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    /** До полностью замурованного блока всё равно ни дойти, ни доломать — пропускаем сразу. */
    private static boolean isExposed(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.hasChunkAt(neighbor)) continue;
            BlockState state = level.getBlockState(neighbor);
            if (state.isAir() || !state.getFluidState().isEmpty()) return true;
        }
        return false;
    }

    private boolean inDeadZone(BlockPos pos) {
        for (BlockPos zone : deadZones) {
            if (pos.distSqr(zone) <= (double) DEAD_ZONE_RADIUS * DEAD_ZONE_RADIUS) return true;
        }
        return false;
    }

    /** Берёт в руку самый быстрый инструмент из хотбара для этого блока. */
    private void selectBestTool(BlockState state) {
        Inventory inv = bot.getInventory();
        int best = inv.getSelectedSlot();
        float bestSpeed = inv.getItem(best).getDestroySpeed(state);
        for (int i = 0; i < 9; i++) {
            float speed = inv.getItem(i).getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = i;
            }
        }
        if (best != inv.getSelectedSlot()) {
            inv.setSelectedSlot(best);
        }
    }

    private void finish() {
        boolean goDeposit = depositAnchor != null && !chestUnreachable && hasAnythingToDeposit();
        tell("§a[бот] ✔ задача «" + jobName + "» завершена!" + (goDeposit ? " Несу добытое на склад…" : ""));
        if (!failed.isEmpty()) {
            tell("§c[бот] не удалось:");
            failed.forEach(f -> tell("§c - " + f));
        }
        failed.clear();
        bot.zza = 0;
        bot.setSprinting(false);
        if (goDeposit) {
            triedChests.clear();
            depositPos = depositAnchor;
            depositReturnPos = bot.blockPosition();
            depositRequested = true;
        }
    }

    private int countItem(Item item) {
        int total = 0;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private void tell(String msg) {
        if (owner == null) return;
        ServerPlayer player = bot.level().getServer().getPlayerList().getPlayer(owner);
        if (player != null) player.sendSystemMessage(Component.literal(msg));
    }

    private ServerLevel level() {
        return (ServerLevel) bot.level();
    }
}
