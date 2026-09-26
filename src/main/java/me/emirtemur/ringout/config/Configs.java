package me.emirtemur.ringout.config;

import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.exception.OkaeriException;
import eu.okaeri.configs.yaml.bukkit.YamlBukkitConfigurer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import me.emirtemur.ringout.util.SafeYaml;

/**
 * Okaeri set-up. File reading and writing is done here, not by Okaeri: the 6.1 beta leaves its
 * streams open on load(File)/save(File), and our writes must stay atomic.
 */
public final class Configs {

    private Configs() {
    }

    /** A config holding its defaults. Unknown keys in a file are kept, not removed. */
    public static <T extends OkaeriConfig> T create(Class<T> type) {
        return ConfigManager.create(type, config -> config
                .withConfigurer(new YamlBukkitConfigurer())
                .withRemoveOrphans(false));
    }

    /**
     * Reads the file into a new config; a missing or empty file gives the defaults. Throws when
     * the file can't be read or parsed, so a broken file is never mistaken for defaults.
     */
    public static <T extends OkaeriConfig> T read(Class<T> type, File file) throws IOException, OkaeriException {
        T config = create(type);
        if (file.isFile()) {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (!content.isBlank()) {
                config.load(content);
            }
        }
        return config;
    }

    /**
     * The whole cause chain of a load error in one line. Okaeri's own message ("failed #load")
     * hides the useful part, like SnakeYAML's line and column of a broken file.
     */
    public static String describe(Throwable error) {
        StringBuilder text = new StringBuilder();
        String previous = null;
        for (Throwable current = error; current != null && current != current.getCause(); current = current.getCause()) {
            String message = current.getMessage();
            if (message == null || message.isBlank() || message.equals(previous)) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(" <- ");
            }
            text.append(message.strip().replaceAll("\\s+", " "));
            previous = message;
        }
        return text.isEmpty() ? error.getClass().getSimpleName() : text.toString();
    }

    /** Writes the whole config atomically, including keys the file did not have yet. */
    public static void write(OkaeriConfig config, File file) throws IOException, OkaeriException {
        SafeYaml.writeAtomically(file, new String(config.saveToBytes(), StandardCharsets.UTF_8));
    }

    /**
     * Like write, but leaves the file alone when it already holds exactly this content. A file in
     * Okaeri's own layout is never touched again on load (or in an editor that has it open); one
     * that differs (missing keys, but also the admin's own comments, CRLF or other quoting) is
     * rewritten once and stays stable after that. Returns true if written.
     */
    public static boolean writeIfChanged(OkaeriConfig config, File file) throws IOException, OkaeriException {
        byte[] content = config.saveToBytes();
        if (file.isFile() && Arrays.equals(Files.readAllBytes(file.toPath()), content)) {
            return false;
        }
        SafeYaml.writeAtomically(file, new String(content, StandardCharsets.UTF_8));
        return true;
    }
}
