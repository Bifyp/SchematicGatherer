package dev.bifyp.schematicgatherer.bot;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
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
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Серверный «мозг» бота: очередь «что добыть» → поиск ближайшего блока →
 * подойти → сломать (прогресс разрушения как у Carpet action pack) → пока не хватит.
 *
 * Движение без A* (прямая + прыжок через одиночный блок), с «мёртвыми зонами»
 * вокруг мест застревания и без спама в чат.
 *
 * Склад (depositPos, задаётся /gatherbot <имя> deposit ...). Разгрузка случается:
 *  - когда инвентарь полностью заполнен во время задачи;
 *  - после завершения задачи (если есть что нести);
 *  - по команде «deposit now» — в любой момент, даже без задачи.
 *
 * Если до склада по прямой не дойти (шахта, стена), бот ПРОКАПЫВАЕТСЯ:
 * туннель 1×2 вперёд, при складе ниже — ступеньки вниз (ломает пол перед собой).
 * Предохранители: не ломает неломаемое, отказывается при лаве в ячейке или рядом
 * с ней, не копает вверх, лимит MAX_TUNNEL_BLOCKS блоков за один заход.
 * Перекладывается всё, кроме предмета в активном слоте (инструмента).
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
    /** Сколько блоков максимум прокапываем за один поход к складу. */
    private static final int MAX_TUNNEL_BLOCKS = 64;

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

    // склад (задаётся командой, живёт пока бот заспавнен)
    private BlockPos depositPos;
    private boolean depositRequested;
    private boolean chestUnreachable;
    private boolean warnedFull;
    private int chestStuckTicks;
    private BlockPos chestLastPos;
    // прокапывание к складу
    private final List<BlockPos> diggingCells = new ArrayList<>();
    private int dugThisTrip;
    private String digFailReason = "";

    public BotBrain(GatherBot bot) {
        this.bot = bot;
    }

    public boolean isRunning() {
        return current != null || !queue.isEmpty();
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
        chestStuckTicks = 0;
        diggingCells.clear();
        dugThisTrip = 0;
        tell("§a[бот] задача «" + name + "»: позиций " + targets.size() + ", радиус поиска " + radius
                + (depositPos == null ? "" : ", склад: " + depositPos.toShortString()));
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
        diggingCells.clear();
        if (report) tell("§e[бот] задача остановлена.");
    }

    public String status() {
        if (!isRunning()) return "нет активной задачи";
        if (current == null) return "«" + jobName + "»: между задачами, в очереди " + queue.size();
        return "«" + jobName + "»: " + current.label() + " " + countItem(current.item()) + "/" + current.needed()
                + ", в очереди " + queue.size();
    }

    // ---------- склад ----------

    public void setDeposit(BlockPos pos) {
        this.depositPos = pos;
        this.chestUnreachable = false;
        this.warnedFull = false;
        this.chestStuckTicks = 0;
        this.diggingCells.clear();
        this.dugThisTrip = 0;
    }

    public void clearDeposit() {
        this.depositPos = null;
        this.chestUnreachable = false;
        this.diggingCells.clear();
    }

    public BlockPos getDeposit() {
        return depositPos;
    }

    /** Ручная команда «deposit now»: дойти до склада и разгрузиться прямо сейчас. */
    public void requestDeposit() {
        chestUnreachable = false; // после ручной команды даём складу второй шанс
        chestStuckTicks = 0;
        dugThisTrip = 0;
        depositRequested = true;
    }

    // ---------- тик ----------

    public void tick() {
        if (bot.isRemoved()) {
            stopJob(false);
            return;
        }

        // разгрузка по команде / после задачи — работает и без активной задачи
        if (depositRequested) {
            abortMining();
            targetPos = null;
            if (depositPos == null || chestUnreachable || depositTick()) {
                depositRequested = false;
            }
            return;
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
            lastPos = bot.blockPosition();
        }

        int have = countItem(current.item());
        if (have >= current.needed()) {
            tell("§a[бот] ✔ " + current.label() + " — есть " + have + unreachableSuffix());
            nextTarget();
            return;
        }

        // инвентарь полон -> идём разгружаться (если склад задан и достижим)
        if (isInventoryFull()) {
            if (depositPos == null || chestUnreachable) {
                if (!warnedFull) {
                    warnedFull = true;
                    tell(depositPos == null
                            ? "§e[бот] инвентарь полон, склад не задан — дропы останутся лежать. "
                              + "Задай: /gatherbot " + bot.getGameProfile().name() + " deposit here (глядя на сундук)"
                            : "§e[бот] инвентарь полон, а до склада не добраться — дропы останутся лежать.");
                }
            } else {
                abortMining();
                targetPos = null;
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

    // ---------- склад: ходьба, прокапывание, разгрузка ----------

    /** @return true — разгрузка завершена (успешно переложили или отказались от попыток). */
    private boolean depositTick() {
        double dist = bot.getEyePosition().distanceTo(Vec3.atCenterOf(depositPos));
        if (dist <= CHEST_REACH) {
            chestStuckTicks = 0;
            diggingCells.clear();
            bot.zza = 0;
            bot.setSprinting(false);
            depositItems();
            return true;
        }

        // режим «прокапываюсь»: ломаем очередную ячейку туннеля
        if (!diggingCells.isEmpty()) {
            BlockPos cell = diggingCells.get(0);
            if (level().getBlockState(cell).isAir() || !level().getFluidState(cell).isEmpty()) {
                diggingCells.remove(0);
                dugThisTrip++;
                return false;
            }
            bot.zza = 0;
            bot.setSprinting(false);
            mine(cell);
            return false;
        }

        // обычная ходьба к складу
        bot.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(depositPos));
        bot.zza = 1.0F;
        bot.setSprinting(true);
        if (bot.horizontalCollision && bot.onGround()) {
            bot.jumpFromGround();
        }

        // стоп, если путь пошёл через лаву
        if (level().getFluidState(bot.blockPosition()).is(FluidTags.LAVA)) {
            bot.zza = 0;
            chestUnreachable = true;
            tell("§c[бот] путь к складу идёт через лаву — стою. Перенеси меня или перезадай склад.");
            return true;
        }

        if (++chestStuckTicks >= STUCK_TICKS) {
            boolean noProgress = chestLastPos != null && bot.blockPosition().distSqr(chestLastPos) < 1.0;
            chestStuckTicks = 0;
            chestLastPos = bot.blockPosition();
            if (noProgress) {
                if (dugThisTrip >= MAX_TUNNEL_BLOCKS) {
                    chestUnreachable = true;
                    tell("§c[бот] прокопал " + dugThisTrip + " блоков и не дошёл — склад слишком далеко. "
                            + "Перенеси меня ближе или перезадай склад.");
                    return true;
                }
                List<BlockPos> plan = planDigCells();
                if (plan == null) {
                    chestUnreachable = true;
                    tell("§c[бот] не могу прокопаться к складу: " + digFailReason + ". Разгрузка выключена.");
                    return true;
                }
                if (plan.isEmpty()) {
                    chestUnreachable = true;
                    tell("§c[бот] застрял, но ломать нечего (впереди пусто) — не могу добраться до склада "
                            + depositPos.toShortString());
                    return true;
                }
                diggingCells.addAll(plan);
                tell("§7[бот] прокапываюсь к складу…");
            }
        }
        return false;
    }

    /**
     * Планирует, какие ячейки сломать для следующего шага к складу.
     * @return список ячеек (может быть пустым) или null, если копать небезопасно/бессмысленно
     *         (причина в digFailReason).
     */
    private List<BlockPos> planDigCells() {
        Direction dir = bot.getDirection(); // в ходьбе бот смотрит на склад
        BlockPos feet = bot.blockPosition();
        BlockPos frontFeet = feet.relative(dir);
        BlockPos frontHead = frontFeet.above();
        int dy = depositPos.getY() - feet.getY();

        if (dy >= 2) {
            digFailReason = "склад выше меня — вверх прокапываться не умею";
            return null;
        }

        List<BlockPos> cells = new ArrayList<>();
        if (dy <= -2
                && level().getBlockState(frontFeet).isAir()
                && level().getBlockState(frontHead).isAir()) {
            // впереди пусто, а склад ниже — ломаем пол перед собой, получается ступенька вниз
            if (!addDigCell(cells, frontFeet.below())) return null;
        } else {
            if (!addDigCell(cells, frontFeet)) return null;
            if (!addDigCell(cells, frontHead)) return null;
        }
        return cells;
    }

    /** @return false, если ячейку ломать нельзя/опасно (причина в digFailReason). */
    private boolean addDigCell(List<BlockPos> cells, BlockPos cell) {
        BlockState state = level().getBlockState(cell);
        if (state.isAir()) return true; // ломать нечего
        if (!state.getFluidState().isEmpty()) {
            if (state.getFluidState().is(FluidTags.LAVA)) {
                digFailReason = "впереди лава";
                return false;
            }
            return true; // вода — просто идём сквозь неё
        }
        if (cell.equals(depositPos)) {
            digFailReason = "вплотную у склада, но не достать — подведи меня ближе";
            return false;
        }
        if (state.getDestroySpeed(level(), cell) < 0.0F) {
            digFailReason = "неломаемый блок (" + state.getBlock().getName().getString() + ")";
            return false;
        }
        // лава в любой соседней ячейке — вскрывать нельзя, вольётся в туннель
        for (Direction d : Direction.values()) {
            if (level().getFluidState(cell.relative(d)).is(FluidTags.LAVA)) {
                digFailReason = "рядом лава — вскрывать стену не буду";
                return false;
            }
        }
        cells.add(cell);
        return true;
    }

    /** Перекладывает в контейнер всё, кроме предмета в активном слоте (инструмента). */
    private void depositItems() {
        if (!(level().getBlockEntity(depositPos) instanceof Container container)) {
            chestUnreachable = true;
            tell("§c[бот] на " + depositPos.toShortString() + " нет контейнера — разгрузка отключена.");
            return;
        }
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
        dugThisTrip = 0;
        if (moved > 0) {
            tell("§7[бот] разгрузился в склад (предметов: " + moved + ")");
        } else {
            chestUnreachable = true;
            tell("§c[бот] склад полон — разгрузка отключена.");
        }
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
        boolean goDeposit = depositPos != null && !chestUnreachable && hasAnythingToDeposit();
        tell("§a[бот] ✔ задача «" + jobName + "» завершена!" + (goDeposit ? " Несу добытое на склад…" : ""));
        if (!failed.isEmpty()) {
            tell("§c[бот] не удалось:");
            failed.forEach(f -> tell("§c - " + f));
        }
        failed.clear();
        bot.zza = 0;
        bot.setSprinting(false);
        depositRequested = goDeposit;
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
