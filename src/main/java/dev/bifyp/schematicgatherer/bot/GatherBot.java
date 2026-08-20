package dev.bifyp.schematicgatherer.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Фейковый игрок-сборщик. Спавн повторяет EntityPlayerMPFake из Carpet:
 * офлайн-UUID, FakeClientConnection, placeNewPlayer — для сервера это полноценный игрок.
 */
public final class GatherBot extends ServerPlayer {

    public final BotBrain brain = new BotBrain(this);

    private GatherBot(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    public static GatherBot spawn(String name, MinecraftServer server, ServerLevel level, Vec3 pos, float yaw, float pitch) {
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        GatherBot bot = new GatherBot(server, level, profile);
        server.getPlayerList().placeNewPlayer(
                new FakeClientConnection(PacketFlow.SERVERBOUND),
                bot,
                new CommonListenerCookie(profile, 0, bot.clientInformation(), false));
        bot.stopRiding();
        bot.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), yaw, pitch, true);
        bot.setHealth(20.0F);
        bot.unsetRemoved();
        bot.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        // чтобы другие игроки увидели бота сразу, а не после первого движения
        server.getPlayerList().broadcastAll(
                new ClientboundRotateHeadPacket(bot, (byte) (bot.yHeadRot * 256 / 360)), level.dimension());
        server.getPlayerList().broadcastAll(ClientboundEntityPositionSyncPacket.of(bot), level.dimension());
        bot.entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);
        return bot;
    }

    @Override
    public void tick() {
        if (this.level().getServer().getTickCount() % 10 == 0) {
            this.connection.resetPosition();
            this.level().getChunkSource().move(this);
        }
        try {
            super.tick();
            // В 26.x физика/движение/подбор предметов ServerPlayer живут в doTick().
            // Настоящим игрокам его вызывает их сетевой слушатель, а слушатель фейка
            // (FakeClientConnection) — заглушка -> зовём сами, как EntityPlayerMPFake
            // в Carpet. Без этого бот просто стоял столбом (баг «бот не двигается»).
            this.doTick();
        } catch (NullPointerException ignored) {
            // как в Carpet: FakeClientConnection местами NPE-шит, игра от этого не падает
        }
        if (this.isAlive()) {
            brain.tick();
        }
    }

    public void kill(Component reason) {
        this.connection.onDisconnect(new DisconnectionDetails(reason));
    }

    @Override
    public void kill(ServerLevel level) {
        kill(Component.literal("Killed"));
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        setHealth(20.0F);
        this.foodData = new FoodData();
        // фейку нечем респауниться — отключаем, как Carpet
        kill(this.getCombatTracker().getDeathMessage());
    }

    @Override
    public String getIpAddress() {
        return "127.0.0.1";
    }
}
