package me.emirtemur.ringout.util;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Careful YAML file updates: a file is re-read right before writing so edits made to it since
 * the last reload are kept, nothing is written while it does not parse, and writes are atomic.
 */
public final class SafeYaml {

    private SafeYaml() {
    }

    /** Why a file was not updated; the level is how loudly it deserves to be logged. */
    public static final class SaveException extends Exception {

        private final Level level;

        SaveException(Level level, String message, Throwable cause) {
            super(message, cause);
            this.level = level;
        }

        public Level level() {
            return level;
        }
    }

    /**
     * Rewrites one section of the file as it is on disk right now (a null path means the whole
     * file). A missing file is created. Throws when the file does not parse, when the path is
     * not a section, or when writing fails; the file is left untouched in all those cases.
     */
    public static void update(File file, String path, Consumer<ConfigurationSection> writer) throws SaveException {
        YamlConfiguration disk = new YamlConfiguration();
        if (file.exists()) {
            try {
                disk.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                throw new SaveException(Level.WARNING,
                        file.getName() + " currently has an error, changes are not saved: " + e.getMessage(), null);
            }
        }
        ConfigurationSection section = disk;
        if (path != null) {
            if (disk.isSet(path) && !disk.isConfigurationSection(path)) {
                throw new SaveException(Level.WARNING,
                        "'" + path + "' in " + file.getName() + " is not a section, changes are not saved.", null);
            }
            section = disk.isConfigurationSection(path) ? disk.getConfigurationSection(path) : disk.createSection(path);
        }
        writer.accept(section);
        try {
            writeAtomically(file, disk.saveToString());
        } catch (IOException e) {
            throw new SaveException(Level.SEVERE, "Could not save " + file.getName(), e);
        }
    }

    /**
     * Writes and syncs a temp file first, then moves it over the target, so neither a crash nor a
     * power loss mid-write can leave a half-written or empty file. The temp file is removed on failure.
     */
    public static void writeAtomically(File file, String content) throws IOException {
        Path target = file.toPath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(file.getName() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException suppressed) {
                // Keep the original write/move error as the reported cause.
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }
}
