package dev.bifyp.schematicgatherer;

import baritone.api.schematic.IStaticSchematic;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Считает, какие блоки и сколько раз встречаются в схематике. */
public final class MaterialCounter {

    public record Result(Map<Block, Integer> materials, List<String> skippedLines) {}

    private MaterialCounter() {}

    public static Result count(IStaticSchematic schem) {
        Map<Block, Integer> materials = new HashMap<>();
        Map<String, Integer> skipped = new HashMap<>();

        for (int y = 0; y < schem.heightY(); y++) {
            for (int z = 0; z < schem.lengthZ(); z++) {
                for (int x = 0; x < schem.widthX(); x++) {
                    if (!schem.inSchematic(x, y, z, null)) continue;
                    BlockState state = schem.desiredState(x, y, z, null, List.of());
                    if (state == null || state.isAir()) continue;
                    Block block = state.getBlock();
                    if (block.asItem() == Items.AIR) {
                        // вода, лава, огонь и т.п. — предмета нет, добыть нельзя
                        skipped.merge(BuiltInRegistries.BLOCK.getKey(block).getPath(), 1, Integer::sum);
                        continue;
                    }
                    materials.merge(block, 1, Integer::sum);
                }
            }
        }

        List<String> skippedLines = skipped.entrySet().stream()
                .map(e -> e.getKey() + " ×" + e.getValue() + " — нет предмета (жидкость/огонь)")
                .toList();
        return new Result(materials, skippedLines);
    }
}
