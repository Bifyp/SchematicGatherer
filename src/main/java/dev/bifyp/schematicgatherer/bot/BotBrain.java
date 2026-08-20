package dev.bifyp.schematicgatherer.bot;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
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
 * Движение по-прежнему без A* (прямая + прыжок через одиночный блок), но:
 *  - место, где бот застрял, становится «мёртвой зоной» радиусом DEAD_ZONE_RADIUS:
 *    недоступная жила вычёркивается целиком, а не по одному блоку (раньше сканер
 *    находил соседний блок той же жилы и бот спамил «не могу подойти» до бесконечности);
 *  - полностью замурованные блоки (ни один сосед не воздух/жидкость) пропускаются сразу;
 *  - чат не спамится: в чат идёт только первая неудача по цели, дальше тихо,
 *    а сводка «пропущено N» — при завершении цели;
 *  - после MAX_DEAD_ZONES мёртвых зон цель признаётся недостижимой и пропускается.
 */
public final class BotBrain {

    private static final double REACH = 4.5;
    private static final int SCAN_INTERVAL = 20;
    private static final int STUCK_TICKS = 60;
    private static final int SCAN_VERTICAL = 12;
    /** Радиус кластера вокруг точки застревания, который считаем недоступным целиком. */
    private static final int DEAD_ZONE_RADIUS = 8;
    /** Сколько мёртвых зон по одной цели терпим, прежде чем сдаться. */
    private static final int MAX_DEAD_ZONES = 60;

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
        tell("§a[бот] задача «" + name + "»: позиций " + targets.size() + ", радиус поиска " + radius);
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
        if (report) tell("§e[бот] задача остановлена.");
    }

    public String status() {
        if (!isRunning()) return "нет активной задачи";
        if (current == null) return "«" + jobName + "»: между задачами, в очереди " + queue.size();
        return "«" + jobName + "»: " + current.label() + " " + countItem(current.item()) + "/" + current.needed()
                + ", в очереди " + queue.size();
    }

    // ---------- тик ----------

    public void tick() {
        if (bot.isRemoved()) {
            stopJob(false);
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
        tell("§a[бот] ✔ задача «" + jobName + "» завершена!");
        if (!failed.isEmpty()) {
            tell("§c[бот] не удалось:");
            failed.forEach(f -> tell("§c - " + f));
        }
        failed.clear();
        bot.zza = 0;
        bot.setSprinting(false);
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
