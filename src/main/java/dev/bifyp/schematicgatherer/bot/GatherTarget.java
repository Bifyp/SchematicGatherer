package dev.bifyp.schematicgatherer.bot;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Одна позиция сбора: какой блок ломать и по какому предмету считать прогресс. */
public record GatherTarget(Block block, Item item, int needed, String label, String note) {}
