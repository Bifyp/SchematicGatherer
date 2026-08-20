package dev.bifyp.schematicgatherer.schematic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Минимальный парсер Sponge .schem (v2/v3): читаем только палитру и BlockData —
 * позиции не нужны, мы просто считаем блоки. Без зависимости от Baritone.
 */
public final class SpongeSchematic {

    private final int width;
    private final int height;
    private final int length;
    private final BlockState[] states;

    private SpongeSchematic(int width, int height, int length, BlockState[] states) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.states = states;
    }

    public int width() { return width; }
    public int height() { return height; }
    public int length() { return length; }

    public BlockState stateAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = states[x + z * width + y * width * length];
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    public static SpongeSchematic read(File file, HolderLookup<Block> blockLookup) throws IOException {
        CompoundTag tag;
        try (InputStream in = new FileInputStream(file)) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        int width = tag.getShort("Width").orElseThrow(() -> new IOException("нет Width"));
        int height = tag.getShort("Height").orElseThrow(() -> new IOException("нет Height"));
        int length = tag.getShort("Length").orElseThrow(() -> new IOException("нет Length"));

        CompoundTag paletteTag = tag.getCompound("Palette").orElseThrow(() -> new IOException("нет Palette"));
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (String key : paletteTag.keySet()) {
            int idx = paletteTag.getInt(key).orElse(0);
            if (idx >= 0 && idx < palette.length) {
                palette[idx] = parseState(blockLookup, key);
            }
        }

        byte[] data = tag.getByteArray("BlockData").orElse(new byte[0]);
        int total = width * height * length;
        BlockState[] states = new BlockState[total];
        int i = 0;
        for (int idx = 0; idx < total; idx++) {
            // BlockData — varint-поток индексов палитры
            int value = 0;
            int shift = 0;
            while (i < data.length) {
                int b = data[i++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            states[idx] = value >= 0 && value < palette.length && palette[value] != null
                    ? palette[value]
                    : Blocks.AIR.defaultBlockState();
        }
        return new SpongeSchematic(width, height, length, states);
    }

    private static BlockState parseState(HolderLookup<Block> lookup, String key) {
        try {
            return BlockStateParser.parseForBlock(lookup, key, false).blockState();
        } catch (CommandSyntaxException e) {
            return Blocks.AIR.defaultBlockState();
        }
    }
}
