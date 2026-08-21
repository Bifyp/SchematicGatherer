package dev.bifyp.schematicgatherer.bot;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Серверная chest-GUI с настройками бота (/gatherbot <имя> gui):
 * клик по иконке — переключить настройку. Предметы достать нельзя
 * (clicked/quickMoveStack глушим), клиенту ничего ставить не нужно.
 */
public final class BotSettingsMenu extends ChestMenu {

    private static final int SIZE = 27;

    private final GatherBot bot;

    public BotSettingsMenu(int containerId, Inventory playerInv, GatherBot bot) {
        super(MenuType.GENERIC_9x3, containerId, playerInv, new SimpleContainer(SIZE), 3);
        this.bot = bot;
        refresh();
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId >= 0 && slotId < SIZE) {
            bot.brain.toggleSetting(slotId);
            refresh();
        }
        // super не вызываем — иконки не достать
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // shift-клик ничего не двигает
    }

    private void refresh() {
        BotBrain brain = bot.brain;
        Container c = getContainer();
        c.setItem(10, icon(Items.ENDER_PEARL, "Телепорт (склад/забор/курьер)", brain.isTeleportEnabled()));
        c.setItem(11, icon(Items.TOTEM_OF_UNDYING, "Неуязвимость", brain.isInvulnerable()));
        c.setItem(12, icon(Items.PAPER, "Тихий режим", brain.isQuiet()));
        c.setItem(13, icon(Items.FURNACE, "Печки: автообслуживание", brain.isAutoSmelt()));
        c.setItem(14, icon(Items.CHEST, "Сток-уровни: автопополнение", brain.isAutoRestock()));
        c.setItem(15, icon(Items.RED_BED, "Домой после задачи", brain.isAutoHome()));
        c.setItem(16, icon(Items.SHIELD, "Защита базы (радиус 12)", brain.getProtectRadius() > 0));
        broadcastChanges();
    }

    private static ItemStack icon(Item item, String name, boolean on) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME,
                Component.literal((on ? "§a" : "§c") + name + ": " + (on ? "ВКЛ" : "ВЫКЛ")));
        return stack;
    }
}
