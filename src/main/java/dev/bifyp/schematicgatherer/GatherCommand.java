package dev.bifyp.schematicgatherer;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeFile;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
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
            logDirect("Использование: " + label + " <схематика> | list <схематика> | status | stop");
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
            return Stream.concat(Stream.of("list", "status", "stop"), RelativeFile.tabComplete(args, schematicsDir));
        }
        if (args.has(2)) {
            if ("list".equalsIgnoreCase(args.getString())) {
                return RelativeFile.tabComplete(args, schematicsDir);
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
                "> gather stop — остановить"
        );
    }
}
