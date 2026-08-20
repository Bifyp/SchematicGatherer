package dev.bifyp.schematicgatherer;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeFile;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Команда #gather: разбор аргументов и делегирование в {@link GatherProcess}.
 * Файл ищется в папке schematics/ — так же, как у встроенной #build.
 */
public final class GatherCommand extends Command {

    private static final double DEPOSIT_RAYCAST = 6.0;

    private final GatherProcess process;
    private final File schematicsDir;

    public GatherCommand(IBaritone baritone, GatherProcess process) {
        super(baritone, "gather");
        this.process = process;
        this.schematicsDir = new File(baritone.getPlayerContext().minecraft().gameDirectory, "schematics");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            process.printStatus();
            logDirect("Использование: " + label + " <схематика> | list <схематика> | status | stop | deposit [here|now|set x y z|clear]");
            return;
        }
        String sub = args.peekString().toLowerCase(Locale.US);
        switch (sub) {
            case "stop", "cancel" -> {
                args.get();
                args.requireMax(0);
                process.cancel();
            }
            case "status" -> {
                args.get();
                args.requireMax(0);
                process.printStatus();
            }
            case "deposit" -> {
                args.get();
                handleDeposit(args);
            }
            case "list" -> {
                args.get();
                args.requireMin(1);
                File file = resolveFile(args);
                args.requireMax(0);
                process.printMaterials(file);
            }
            default -> {
                File file = resolveFile(args);
                args.requireMax(0);
                process.start(file);
            }
        }
    }

    // ---------- склад ----------

    private void handleDeposit(IArgConsumer args) throws CommandException {
        if (!args.hasAny()) {
            BlockPos pos = process.getDeposit();
            logDirect(pos == null
                    ? "Склад не задан. Задай: deposit here (глядя на сундук) или deposit set <x> <y> <z>"
                    : "Склад: " + pos.toShortString() + " (разгрузиться сейчас: deposit now)");
            return;
        }
        String sub = args.getString().toLowerCase(Locale.US);
        switch (sub) {
            case "here" -> {
                args.requireMax(0);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || mc.level == null) {
                    throw new CommandInvalidStateException("нет загруженного мира");
                }
                HitResult hit = mc.player.pick(DEPOSIT_RAYCAST, 0.0F, false);
                if (hit.getType() != HitResult.Type.BLOCK) {
                    throw new CommandInvalidStateException("смотри на сундук/бочку (до " + (int) DEPOSIT_RAYCAST + " блоков)");
                }
                setDeposit(((BlockHitResult) hit).getBlockPos());
            }
            case "set" -> {
                args.requireMin(3);
                int x = parseCoord(args.getString());
                int y = parseCoord(args.getString());
                int z = parseCoord(args.getString());
                args.requireMax(0);
                setDeposit(new BlockPos(x, y, z));
            }
            case "now" -> {
                args.requireMax(0);
                if (process.requestDeposit()) {
                    logDirect("Пошёл разгружаться на склад");
                }
            }
            case "clear" -> {
                args.requireMax(0);
                process.clearDeposit();
                logDirect("Склад сброшен — буду копить всё в инвентаре");
            }
            default -> throw new CommandInvalidStateException("deposit: here | now | set <x> <y> <z> | clear");
        }
    }

    private void setDeposit(BlockPos pos) throws CommandException {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getBlockEntity(pos) instanceof Container)) {
            throw new CommandInvalidStateException(pos.toShortString() + " — не контейнер (нужен сундук/бочка и т.п.)");
        }
        process.setDeposit(pos);
        logDirect("Склад: " + pos.toShortString());
    }

    private static int parseCoord(String s) throws CommandException {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new CommandInvalidStateException("координата не число: " + s);
        }
    }

    private File resolveFile(IArgConsumer args) throws CommandException {
        File file0 = args.getDatatypePost(RelativeFile.INSTANCE, schematicsDir).getAbsoluteFile();
        File file = file0;
        if (FilenameUtils.getExtension(file.getAbsolutePath()).isEmpty()) {
            file = new File(file.getAbsolutePath() + "." + BaritoneAPI.getSettings().schematicFallbackExtension.value);
        }
        if (!file.exists()) {
            throw new CommandInvalidStateException("Не найден файл схематики: " + file.getName());
        }
        if (BaritoneAPI.getProvider().getSchematicSystem().getByFile(file).isEmpty()) {
            throw new CommandInvalidStateException("Неподдерживаемый формат схематики: " + file.getName());
        }
        return file;
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return Stream.concat(Stream.of("list", "status", "stop", "deposit"), RelativeFile.tabComplete(args, schematicsDir));
        }
        if (args.has(2)) {
            String first = args.getString();
            if ("list".equalsIgnoreCase(first)) {
                return RelativeFile.tabComplete(args, schematicsDir);
            }
            if ("deposit".equalsIgnoreCase(first)) {
                return Stream.of("here", "now", "set", "clear");
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Собрать все ресурсы для схематики";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Gather читает схематику, считает все блоки и добывает нужные ресурсы через #mine.",
                "",
                "Использование:",
                "> gather <файл> — начать сбор ресурсов",
                "> gather list <файл> — только показать список материалов",
                "> gather status — прогресс",
                "> gather stop — остановить (останавливает и разгрузку)",
                "> gather deposit — показать заданный склад",
                "> gather deposit here — запомнить сундук, на который смотришь",
                "> gather deposit set <x> <y> <z> — задать склад координатами",
                "> gather deposit now — разгрузиться на склад прямо сейчас",
                "> gather deposit clear — сбросить склад",
                "",
                "Когда склад задан, при полном инвентаре (и после конца сбора) игрок сам идёт",
                "к сундуку через Baritone — при необходимости прокапываясь, — выгружает всё,",
                "кроме инструмента в руке, и продолжает с того же места."
        );
    }
}
