package dev.bifyp.schematicgatherer.bot;

import dev.bifyp.schematicgatherer.ResourceMapper;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Серверный «мозг» бота: очередь «что добыть» -> поиск ближайшего блока ->
 * подойти -> сломать -> пока не хватит.
 *
 * Склад — двусторонний, с телепортом (выключаемо) и самосортировкой.
 * Доп. режимы: quarry (выкапывает всё внутри region), scan, transfer, bring.
 * Фон: сток-уровни (auto-restock) и автообслуживание печек (autoSmelt).
 * Настройки переключаются командами и chest-GUI (/gatherbot <имя> gui).
 */
public final class BotBrain {

    private static final double REACH = 4.5;
    private static final double CHEST_REACH = 3.0;
    private static final int SCAN_INTERVAL = 20;
    private static final int STUCK_TICKS = 60;
    private static final int SCAN_VERTICAL = 12;
    private static final int DEAD_ZONE_RADIUS = 8;
    private static final int MAX_DEAD_ZONES = 60;
    private static final int WAREHOUSE_RADIUS = 8;
    private static final int WAREHOUSE_VERTICAL = 3;
    private static final int DEFAULT_PROTECT_RADIUS = 12;
    private static final long MAX_QUARRY_VOLUME = 128000;
    private static final int RESTOCK_INTERVAL = 600; // 30 секунд
    private static final int SMELT_INTERVAL = 400;   // 20 секунд
    /** Слотов основного инвентаря игрока (без брони и второй руки). */
    private static final int PLAYER_MAIN_SLOTS = 36;

    /** Правило сток-уровня: держать на складе минимум min предметов item (добывается block). */
    public record StockRule(Block block, Item item, int min) {}

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

    // склад
    private BlockPos depositAnchor;
    private BlockPos depositReturnPos;
    private boolean depositRequested;
    private boolean chestUnreachable;
    private boolean warnedFull;
    private boolean needWithdraw;
    private boolean toolFetchTried;
    private int chestStuckTicks;
    private BlockPos chestLastPos;

    // безопасность базы
    private int protectRadius = DEFAULT_PROTECT_RADIUS;
    private BlockPos regionA;
    private BlockPos regionB;

    // настройки (GUI/команды, персистятся)
    private boolean teleportEnabled = true;
    private boolean invulnerable = true;
    private boolean quiet;
    private boolean autoSmelt;
    private boolean autoRestock = true;
    private boolean autoHome;
    private BlockPos homePos;

    // сток-уровни
    private final List<StockRule> stockRules = new ArrayList<>();
    private int restockCooldown;
    private int smeltCooldown;

    // статистика
    private int sessionBlocks;
    private int sessionDeposited;
    private int totalBlocks;
    private int totalDeposited;

    // карьер
    private boolean quarryMode;
    private int qMinX, qMaxX, qMinY, qMaxY, qMinZ, qMaxZ, qX, qY, qZ;

    public BotBrain(GatherBot bot) {
        this.bot = bot;
    }

    // ---------- настройки ----------

    public boolean isTeleportEnabled() { return teleportEnabled; }
    public void setTeleportEnabled(boolean v) { teleportEnabled = v; }
    public boolean isInvulnerable() { return invulnerable; }
    public void setInvulnerable(boolean v) { invulnerable = v; bot.setInvulnerable(v); }
    public boolean isQuiet() { return quiet; }
    public void setQuiet(boolean v) { quiet = v; }
    public boolean isAutoSmelt() { return autoSmelt; }
    public void setAutoSmelt(boolean v) { autoSmelt = v; }
    public boolean isAutoRestock() { return autoRestock; }
    public void setAutoRestock(boolean v) { autoRestock = v; }
    public boolean isAutoHome() { return autoHome; }
    public void setAutoHome(boolean v) { autoHome = v; }

    /** Переключение из chest-GUI (BotSettingsMenu): слот -> настройка. */
    public void toggleSetting(int slot) {
        switch (slot) {
            case 10 -> teleportEnabled = !teleportEnabled;
            case 11 -> setInvulnerable(!invulnerable);
            case 12 -> quiet = !quiet;
            case 13 -> autoSmelt = !autoSmelt;
            case 14 -> autoRestock = !autoRestock;
            case 15 -> autoHome = !autoHome;
            case 16 -> protectRadius = protectRadius > 0 ? 0 : DEFAULT_PROTECT_RADIUS;
            default -> { return; }
        }
        BotPersistence.save(bot.level().getServer());
    }

    // ---------- статистика ----------

    public int getSessionBlocks() { return sessionBlocks; }
    public int getSessionDeposited() { return sessionDeposited; }
    public int getTotalBlocks() { return totalBlocks; }
    public int getTotalDeposited() { return totalDeposited; }
    public void setTotals(int blocks, int deposited) { this.totalBlocks = blocks; this.totalDeposited = deposited; }

    // ---------- дом ----------

    public BlockPos getHome() { return homePos; }
    public void setHome(BlockPos pos) { this.homePos = pos; }

    /** Телепорт домой (точка спавна). */
    public void goHome() {
        if (homePos == null) {
            tell("§e[бот] дом не задан (ставится автоматически при спавне)");
            return;
        }
        abortMining();
        bot.zza = 0;
        bot.setSprinting(false);
        teleportBack(homePos);
        tell("§7[бот] дома");
    }

    // ---------- сток-уровни ----------

    public List<StockRule> getStockRules() { return stockRules; }

    public void addStockRule(Block block, Item item, int min) {
        stockRules.removeIf(r -> r.item() == item);
        stockRules.add(new StockRule(block, item, min));
    }

    public void clearStockRules() { stockRules.clear(); }

    // ---------- состояние ----------

    public boolean isRunning() {
        return current != null || !queue.isEmpty() || quarryMode;
    }

    public boolean isPaused() { return paused; }

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
        needWithdraw = depositAnchor != null && teleportEnabled;
        tell("§a[бот] задача «" + name + "»: позиций " + targets.size() + ", радиус поиска " + radius
                + (depositAnchor == null ? "" : ", склад: " + depositAnchor.toShortString())
                + (regionA != null && regionB != null ? ", зона задана" : ""));
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
        quarryMode = false;
        if (report) tell("§e[бот] задача остановлена.");
    }

    public String status() {
        if (paused) return "на паузе («" + jobName + "», в очереди " + queue.size() + "). Продолжить: resume";
        if (quarryMode) return "карьер: выкапываю зону " + qMinX + "," + qMinZ + " .. " + qMaxX + "," + qMaxZ;
        if (!isRunning()) return "нет активной задачи";
        if (current == null) return "«" + jobName + "»: между задачами, в очереди " + queue.size();
        return "«" + jobName + "»: " + current.label() + " " + countItem(current.item()) + "/" + current.needed()
                + ", в очереди " + queue.size();
    }

    // ---------- skip ----------

    public boolean skipCurrent() {
        if (current == null) return false;
        tell("§e[бот] пропускаю: " + current.label());
        nextTarget();
        return true;
    }

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

    // ---------- безопасность базы ----------

    public int getProtectRadius() { return protectRadius; }
    public void setProtectRadius(int radius) { this.protectRadius = Math.max(0, radius); }
    public BlockPos getRegionA() { return regionA; }
    public BlockPos getRegionB() { return regionB; }
    public void setRegionA(BlockPos pos) { this.regionA = pos; }
    public void setRegionB(BlockPos pos) { this.regionB = pos; }
    public void clearRegion() { this.regionA = null; this.regionB = null; }

    // ---------- склад ----------

    public void setDeposit(BlockPos pos) {
        this.depositAnchor = pos;
        this.chestUnreachable = false;
        this.warnedFull = false;
    }

    public void clearDeposit() {
        this.depositAnchor = null;
        this.chestUnreachable = false;
    }

    public BlockPos getDeposit() { return depositAnchor; }

    public void requestDeposit() {
        chestUnreachable = false;
        depositReturnPos = bot.blockPosition();
        depositRequested = true;
    }

    /** Курьер: взять со склада предмет и принести игроку. Требует телепорт. */
    public void bring(ServerPlayer player, Item item, int amount) {
        String name = BuiltInRegistries.ITEM.getKey(item).getPath();
        if (depositAnchor == null) {
            player.sendSystemMessage(Component.literal("§c[бот] склад не задан. Сначала: deposit here/set"));
            return;
        }
        if (!teleportEnabled) {
            player.sendSystemMessage(Component.literal("§c[бот] телепорт выключен (gui) — bring недоступен"));
            return;
        }
        abortMining();
        BlockPos back = bot.blockPosition();
        teleportNear(depositAnchor);
        int got = takeFromWarehouse(item, amount);
        bot.teleportTo(level(), player.getX(), player.getY(), player.getZ(), Set.of(), bot.getYRot(), bot.getXRot(), true);
        if (got > 0) giveTo(player, item, got);
        teleportBack(back);
        if (got == 0) {
            player.sendSystemMessage(Component.literal("§c[бот] на складе нет «" + name + "»"));
        } else if (got < amount) {
            player.sendSystemMessage(Component.literal("§e[бот] принёс «" + name + "» ×" + got + " — на складе было только столько"));
        } else {
            player.sendSystemMessage(Component.literal("§a[бот] принёс «" + name + "» ×" + got));
        }
    }

    private int takeFromWarehouse(Item item, int amount) {
        int got = 0;
        for (BlockPos chestPos : findAllChests()) {
            if (got >= amount) break;
            if (!(level().getBlockEntity(chestPos) instanceof Container container)) continue;
            for (int i = 0; i < container.getContainerSize() && got < amount; i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty() || !stack.is(item)) continue;
                int take = Math.min(stack.getCount(), amount - got);
                container.removeItem(i, take);
                got += take;
                container.setChanged();
            }
        }
        return got;
    }

    private static void giveTo(ServerPlayer player, Item item, int count) {
        int max = item.getDefaultMaxStackSize();
        while (count > 0) {
            int n = Math.min(count, max);
            ItemStack stack = new ItemStack(item, n);
            insertInto(player.getInventory(), stack);
            if (!stack.isEmpty()) player.drop(stack, false);
            count -= n;
        }
    }

    /**
     * Кладёт стак в контейнер вручную: сначала докладываем в частичные стаки, потом в пустые слоты.
     * В 26.1 у Inventory больше нет addItem(ItemStack), а Container-методы стабильны.
     * Переданный стак уменьшается на положенное количество (остаток = что не влезло).
     */
    private static void insertInto(Container inv, ItemStack stack) {
        if (stack.isEmpty()) return;
        // у инвентаря игрока за 36 слотами идут броня и вторая рука — туда не лезем
        int size = inv instanceof Inventory ? Math.min(inv.getContainerSize(), PLAYER_MAIN_SLOTS) : inv.getContainerSize();
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
            int room = Math.min(slot.getMaxStackSize(), inv.getMaxStackSize()) - slot.getCount();
            if (room <= 0) continue;
            int move = Math.min(room, stack.getCount());
            slot.grow(move);
            stack.shrink(move);
        }
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (!inv.getItem(i).isEmpty()) continue;
            int move = Math.min(Math.min(stack.getMaxStackSize(), inv.getMaxStackSize()), stack.getCount());
            ItemStack put = stack.copy();
            put.setCount(move);
            inv.setItem(i, put);
            stack.shrink(move);
        }
        inv.setChanged();
    }

    /** Остаток, который никуда не влез: вернуть в исходный контейнер, в крайнем случае — бросить под ноги. */
    private void returnLeftover(Container source, ItemStack leftover) {
        if (leftover.isEmpty()) return;
        insertInto(source, leftover);
        if (!leftover.isEmpty()) bot.drop(leftover, false);
    }

    /** Перенос: всё из контейнеров вокруг указанной точки — на склад (переезд хранилища). */
    public void transferFrom(BlockPos center) {
        if (depositAnchor == null) {
            tell("§c[бот] склад не задан — сначала deposit here/set");
            return;
        }
        List<BlockPos> dest = findAllChests();
        if (dest.isEmpty()) {
            tell("§c[бот] на складе нет контейнеров");
            return;
        }
        List<BlockPos> source = findContainersAround(center, WAREHOUSE_RADIUS, WAREHOUSE_VERTICAL);
        source.removeIf(dest::contains);
        int moved = 0;
        for (BlockPos p : source) {
            if (!(level().getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) continue;
                ItemStack rem = insertSorted(dest, s.copy());
                int left = rem.isEmpty() ? 0 : rem.getCount();
                moved += s.getCount() - left;
                c.setItem(i, left == 0 ? ItemStack.EMPTY : rem);
                c.setChanged();
            }
        }
        tell(moved > 0 ? "§a[бот] перенёс на склад: " + moved + " предм." : "§e[бот] рядом со мной нечего переносить");
    }

    // ---------- карьер ----------

    /** Карьер: выкапывает ВСЁ внутри region слоями сверху вниз. @return false — зона не задана/велика. */
    public boolean startQuarry(UUID owner) {
        if (regionA == null || regionB == null) return false;
        stopJob(false);
        this.owner = owner;
        this.jobName = "quarry";
        long vol = (long) (Math.abs(regionA.getX() - regionB.getX()) + 1)
                * (Math.abs(regionA.getY() - regionB.getY()) + 1)
                * (Math.abs(regionA.getZ() - regionB.getZ()) + 1);
        if (vol > MAX_QUARRY_VOLUME) {
            tell("§c[бот] зона слишком большая для карьера (" + vol + " блоков, макс. " + MAX_QUARRY_VOLUME + ")");
            return false;
        }
        qMinX = Math.min(regionA.getX(), regionB.getX()); qMaxX = Math.max(regionA.getX(), regionB.getX());
        qMinY = Math.min(regionA.getY(), regionB.getY()); qMaxY = Math.max(regionA.getY(), regionB.getY());
        qMinZ = Math.min(regionA.getZ(), regionB.getZ()); qMaxZ = Math.max(regionA.getZ(), regionB.getZ());
        qX = qMinX; qY = qMaxY; qZ = qMinZ;
        quarryMode = true;
        deadZones.clear();
        skippedUnreachable = 0;
        stuckTicks = 0;
        targetPos = null;
        warnedFull = false;
        tell("§a[бот] карьер: " + vol + " блоков, слоями сверху вниз. Стоп: stop");
        return true;
    }

    private void quarryTick() {
        if (isInventoryFull()) {
            if (depositAnchor == null || chestUnreachable) {
                if (!warnedFull) {
                    warnedFull = true;
                    tell("§e[бот] инвентарь полон, а склад " + (depositAnchor == null ? "не задан" : "забит") + " — дропы останутся лежать.");
                }
            } else {
                abortMining();
                targetPos = null;
                if (depositReturnPos == null) depositReturnPos = bot.blockPosition();
                depositTick();
            }
            return;
        }
        if (targetPos == null) {
            targetPos = nextQuarryBlock();
            if (targetPos == null) {
                quarryMode = false;
                tell("§a[бот] ✔ карьер выкопан!" + unreachableSuffix());
                if (depositAnchor != null && hasAnythingToDeposit()) {
                    depositReturnPos = bot.blockPosition();
                    depositRequested = true;
                }
                return;
            }
            stuckTicks = 0;
            lastPos = bot.blockPosition();
        }
        BlockState state = level().getBlockState(targetPos);
        if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level(), targetPos) < 0) {
            targetPos = null;
            return;
        }
        double dist = bot.getEyePosition().distanceTo(Vec3.atCenterOf(targetPos));
        if (dist > REACH) {
            walkTo(targetPos);
        } else {
            bot.zza = 0;
            bot.setSprinting(false);
            fetchToolIfNeeded(state);
            mine(targetPos);
        }
    }

    /** Следующий блок карьера: слоями сверху вниз, пропуская пустоту/жидкости/неломаемое. */
    private BlockPos nextQuarryBlock() {
        ServerLevel level = level();
        while (qY >= qMinY) {
            BlockPos pos = new BlockPos(qX, qY, qZ);
            if (++qZ > qMaxZ) {
                qZ = qMinZ;
                if (++qX > qMaxX) {
                    qX = qMinX;
                    qY--;
                }
            }
            if (!level.hasChunkAt(pos)) continue;
            if (inDeadZone(pos) || isProtected(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !state.getFluidState().isEmpty()) continue;
            if (state.getDestroySpeed(level, pos) < 0) continue;
            return pos;
        }
        return null;
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

        // карьер — отдельный режим
        if (quarryMode) {
            quarryTick();
            return;
        }

        if (current == null) {
            if (queue.isEmpty()) {
                idleTick(); // свободен — фоновые задачи склада
                return;
            }
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

        // инвентарь полон -> разгрузка на складе
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
            if (targetPos == null) return;
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

    /** Фоновые задачи склада, когда бот свободен: сток-уровни и печки. */
    private void idleTick() {
        if (depositAnchor == null) return;
        if (autoRestock && !stockRules.isEmpty() && --restockCooldown <= 0) {
            restockCooldown = RESTOCK_INTERVAL;
            checkStock();
            return;
        }
        if (autoSmelt && --smeltCooldown <= 0) {
            smeltCooldown = SMELT_INTERVAL;
            int n = smeltPass();
            if (n > 0) tell("§7[бот] печки обслужены (действий: " + n + ")");
        }
    }

    /** Сток-уровни: если на складе предмета меньше минимума — сам едет добывать разницу. */
    private void checkStock() {
        for (StockRule rule : stockRules) {
            int deficit = rule.min() - warehouseCount(rule.item()) - countItem(rule.item());
            if (deficit <= 0) continue;
            Optional<ResourceMapper.Target> mapped = ResourceMapper.map(rule.block(), deficit);
            if (mapped.isEmpty()) continue;
            ResourceMapper.Target t = mapped.get();
            String label = BuiltInRegistries.ITEM.getKey(rule.item()).getPath();
            tell("§7[бот] сток «" + label + "» просел ниже " + rule.min() + " — добиваю " + deficit);
            startJob("restock " + label, List.of(new GatherTarget(t.block(), t.item(), t.amount(), label, t.note())), owner, radius);
            return; // одна цель за проверку
        }
    }

    private int warehouseCount(Item item) {
        int total = 0;
        for (BlockPos p : findAllChests()) {
            if (!(level().getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.is(item)) total += s.getCount();
            }
        }
        return total;
    }

    // ---------- печки ----------

    /** Обслуживание печек склада: забрать готовое на склад, догрузить вход и топливо. */
    public int smeltPass() {
        List<BlockPos> furnaces = findAllFurnaces();
        if (furnaces.isEmpty()) return 0;
        List<BlockPos> chests = findAllChests();
        BlockPos back = bot.blockPosition();
        if (teleportEnabled) teleportNear(depositAnchor);
        int acted = 0;
        for (BlockPos p : furnaces) {
            if (!(level().getBlockEntity(p) instanceof AbstractFurnaceBlockEntity furnace)) continue;
            // готовое — на склад
            ItemStack out = furnace.getItem(2);
            if (!out.isEmpty() && !chests.isEmpty()) {
                ItemStack rem = insertSorted(chests, out.copy());
                int left = rem.isEmpty() ? 0 : rem.getCount();
                if (left < out.getCount()) {
                    furnace.setItem(2, left == 0 ? ItemStack.EMPTY : rem);
                    furnace.setChanged();
                    acted++;
                }
            }
            // вход
            if (furnace.getItem(0).isEmpty()) {
                ItemStack in = takeByPredicate(this::isSmeltable, 64);
                if (!in.isEmpty()) {
                    furnace.setItem(0, in);
                    furnace.setChanged();
                    acted++;
                }
            }
            // топливо
            if (furnace.getItem(1).isEmpty()) {
                ItemStack fuel = takeByPredicate(BotBrain::isFuelItem, 32);
                if (!fuel.isEmpty()) {
                    furnace.setItem(1, fuel);
                    furnace.setChanged();
                    acted++;
                }
            }
        }
        if (teleportEnabled) teleportBack(back);
        return acted;
    }

    /**
     * Топливо для печек — намеренно узкий список: уголь, древесный уголь, блок угля,
     * сушёная ламинария, стержень ифрита. Доски и брёвна не жжём: это стройматериал,
     * который бот сам и добывал.
     */
    private static boolean isFuelItem(ItemStack stack) {
        return stack.is(Items.COAL)
                || stack.is(Items.CHARCOAL)
                || stack.is(Items.COAL_BLOCK)
                || stack.is(Items.DRIED_KELP_BLOCK)
                || stack.is(Items.BLAZE_ROD);
    }

    private boolean isSmeltable(ItemStack stack) {
        return level().getServer().getRecipeManager()
                .getRecipeFor(RecipeType.SMELTING, new SingleRecipeInput(stack), level())
                .isPresent();
    }

    /** Берёт из склада предметы по предикату (для печек). */
    private ItemStack takeByPredicate(Predicate<ItemStack> pred, int amount) {
        ItemStack collected = ItemStack.EMPTY;
        for (BlockPos p : findAllChests()) {
            if (collected.getCount() >= amount) break;
            if (!(level().getBlockEntity(p) instanceof Container c)) continue;
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (s.isEmpty() || !pred.test(s)) continue;
                if (collected.isEmpty()) {
                    collected = c.removeItem(i, Math.min(s.getCount(), amount));
                } else if (ItemStack.isSameItemSameComponents(collected, s)) {
                    ItemStack t = c.removeItem(i, Math.min(s.getCount(), amount - collected.getCount()));
                    collected.grow(t.getCount());
                }
                c.setChanged();
            }
        }
        return collected;
    }

    private List<BlockPos> findAllFurnaces() {
        List<BlockPos> out = new ArrayList<>();
        if (depositAnchor == null) return out;
        ServerLevel level = level();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -WAREHOUSE_VERTICAL; dy <= WAREHOUSE_VERTICAL; dy++) {
            for (int dx = -WAREHOUSE_RADIUS; dx <= WAREHOUSE_RADIUS; dx++) {
                for (int dz = -WAREHOUSE_RADIUS; dz <= WAREHOUSE_RADIUS; dz++) {
                    pos.set(depositAnchor.getX() + dx, depositAnchor.getY() + dy, depositAnchor.getZ() + dz);
                    if (!level.hasChunkAt(pos)) continue;
                    if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity) out.add(pos.immutable());
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(depositAnchor)));
        return out;
    }

    // ---------- цели ----------

    private void giveUpCurrent() {
        String reason = deadZones.isEmpty()
                ? "не найден в радиусе " + radius + (regionA != null && regionB != null ? " в заданной зоне" : "")
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

    private void markUnreachable(BlockPos pos) {
        deadZones.add(pos);
        skippedUnreachable++;
        if (skippedUnreachable == 1) {
            tell("§e[бот] не могу подойти к " + pos.toShortString() + " — вычёркиваю участок"
                    + (current != null ? " и ищу другой " + current.label() : ""));
        }
        if (current != null && deadZones.size() >= MAX_DEAD_ZONES) {
            giveUpCurrent();
        }
    }

    private String unreachableSuffix() {
        return skippedUnreachable == 0 ? "" : " (недоступных блоков пропущено: " + skippedUnreachable + ")";
    }

    // ---------- склад: забор и инструменты ----------

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
                Item takenItem = taken.getItem();
                int before = taken.getCount();
                insertInto(bot.getInventory(), taken);
                int used = before - taken.getCount();
                returnLeftover(container, taken);
                if (used > 0) {
                    need.merge(takenItem, -used, Integer::sum);
                    took += used;
                }
                container.setChanged();
            }
        }
        teleportBack(back);
        if (took > 0) tell("§7[бот] забрал со склада " + took + " предм. — добуду только остаток");
    }

    private void fetchToolIfNeeded(BlockState state) {
        if (toolFetchTried || depositAnchor == null || !teleportEnabled) return;
        Inventory inv = bot.getInventory();
        float best = 1.0F;
        for (int i = 0; i < 9; i++) {
            best = Math.max(best, inv.getItem(i).getDestroySpeed(state));
        }
        if (best > 1.0F) return;
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
                insertInto(bot.getInventory(), taken);
                if (taken.getCount() < before) found = true;
                returnLeftover(container, taken);
                container.setChanged();
                break outer;
            }
        }
        teleportBack(back);
        if (found) tell("§7[бот] взял инструмент со склада");
    }

    // ---------- склад: разгрузка ----------

    /** @return true — разгрузка завершена. */
    private boolean depositTick() {
        double dist = bot.getEyePosition().distanceTo(Vec3.atCenterOf(depositAnchor));
        if (dist > CHEST_REACH) {
            if (teleportEnabled) {
                teleportNear(depositAnchor);
            } else {
                // пешком (телепорт выключен в настройках)
                bot.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(depositAnchor));
                bot.zza = 1.0F;
                bot.setSprinting(true);
                if (bot.horizontalCollision && bot.onGround()) {
                    bot.jumpFromGround();
                }
                if (++chestStuckTicks >= STUCK_TICKS) {
                    if (chestLastPos != null && bot.blockPosition().distSqr(chestLastPos) < 1.0) {
                        chestUnreachable = true;
                        tell("§c[бот] не могу дойти до склада (телепорт выключен). Разгрузка отключена.");
                        return true;
                    }
                    chestStuckTicks = 0;
                    chestLastPos = bot.blockPosition();
                }
            }
            return false;
        }
        bot.zza = 0;
        bot.setSprinting(false);
        depositItems();
        returnHome();
        return true;
    }

    /** Разгрузка по всему складу с самосортировкой: предмет — туда, где такой уже лежит. */
    private void depositItems() {
        List<BlockPos> chests = findAllChests();
        if (chests.isEmpty()) {
            chestUnreachable = true;
            tell("§c[бот] на складе нет контейнеров — разгрузка отключена.");
            return;
        }
        int moved = 0;
        Inventory inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (i == inv.getSelectedSlot()) continue;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            ItemStack remainder = insertSorted(chests, stack.copy());
            int left = remainder.isEmpty() ? 0 : remainder.getCount();
            moved += stack.getCount() - left;
            inv.setItem(i, left == 0 ? ItemStack.EMPTY : remainder);
        }
        if (moved > 0) {
            sessionDeposited += moved;
            totalDeposited += moved;
        }
        if (!hasAnythingToDeposit()) {
            tell(moved > 0
                    ? "§7[бот] разгрузился на склад (предметов: " + moved + ", разложено по местам)"
                    : "§7[бот] нечего складывать");
        } else {
            chestUnreachable = true;
            tell("§c[бот] склад заполнен — влезло только " + moved + ". Разгрузка отключена.");
        }
    }

    private ItemStack insertSorted(List<BlockPos> chests, ItemStack stack) {
        Container firstWithSpace = null;
        for (BlockPos p : chests) {
            if (stack.isEmpty()) break;
            if (!(level().getBlockEntity(p) instanceof Container c)) continue;
            if (firstWithSpace == null && hasFreeSlot(c)) firstWithSpace = c;
            if (containsMergeable(c, stack)) {
                stack = HopperBlockEntity.addItem(null, c, stack, null);
                c.setChanged();
            }
        }
        if (!stack.isEmpty() && firstWithSpace != null) {
            stack = HopperBlockEntity.addItem(null, firstWithSpace, stack, null);
            firstWithSpace.setChanged();
        }
        return stack;
    }

    private static boolean containsMergeable(Container c, ItemStack stack) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty() && s.getCount() < s.getMaxStackSize() && ItemStack.isSameItemSameComponents(s, stack)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFreeSlot(Container c) {
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).isEmpty()) return true;
        }
        return false;
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

    private void returnHome() {
        if (depositReturnPos != null) {
            teleportBack(depositReturnPos);
            depositReturnPos = null;
        }
    }

    private List<BlockPos> findAllChests() {
        return depositAnchor == null ? List.of() : findContainersAround(depositAnchor, WAREHOUSE_RADIUS, WAREHOUSE_VERTICAL);
    }

    /** Все контейнеры вокруг точки (печки — НЕ склад, исключены), ближайшие первыми. */
    private List<BlockPos> findContainersAround(BlockPos center, int r, int v) {
        List<BlockPos> out = new ArrayList<>();
        ServerLevel level = level();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -v; dy <= v; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.hasChunkAt(pos)) continue;
                    if (!(level.getBlockEntity(pos) instanceof Container)) continue;
                    if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity) continue;
                    out.add(pos.immutable());
                }
            }
        }
        out.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
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

    /** Ломание блока — порт ActionType.ATTACK из Carpet. */
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
            sessionBlocks++;
            totalBlocks++;
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
                    if (isProtected(pos)) continue;
                    if (!inRegion(pos)) continue;
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

    private boolean isProtected(BlockPos pos) {
        return depositAnchor != null && protectRadius > 0
                && pos.distSqr(depositAnchor) <= (double) protectRadius * protectRadius;
    }

    private boolean inRegion(BlockPos pos) {
        if (regionA == null || regionB == null) return true;
        return pos.getX() >= Math.min(regionA.getX(), regionB.getX()) && pos.getX() <= Math.max(regionA.getX(), regionB.getX())
                && pos.getY() >= Math.min(regionA.getY(), regionB.getY()) && pos.getY() <= Math.max(regionA.getY(), regionB.getY())
                && pos.getZ() >= Math.min(regionA.getZ(), regionB.getZ()) && pos.getZ() <= Math.max(regionA.getZ(), regionB.getZ());
    }

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
            depositReturnPos = bot.blockPosition();
            depositRequested = true;
        } else if (autoHome && homePos != null) {
            goHome();
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
        if (quiet && !msg.startsWith("§c")) return; // тихий режим: только ошибки
        if (owner == null) return;
        ServerPlayer player = bot.level().getServer().getPlayerList().getPlayer(owner);
        if (player != null) player.sendSystemMessage(Component.literal(msg));
    }

    private ServerLevel level() {
        return (ServerLevel) bot.level();
    }
}
