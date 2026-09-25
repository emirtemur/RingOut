package me.emirtemur.ringout.game;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Keeps player snapshots on disk (plugins/RingOut/snapshots/&lt;uuid&gt;.yml) so a crash or
 * restart mid-game never loses anyone's inventory.
 */
public final class SnapshotStore {

    private final File directory;
    private final Logger log;

    public SnapshotStore(File directory, Logger log) {
        this.directory = directory;
        this.log = log;
    }

    /** Returns false if the snapshot could not be written, in which case the player must not join. */
    public boolean save(UUID id, PlayerSnapshot snapshot) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            log.severe("Could not create " + directory);
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        snapshot.save(yaml);
        try {
            yaml.save(file(id));
            return true;
        } catch (IOException e) {
            log.log(Level.SEVERE, "Could not save the snapshot of " + id, e);
            return false;
        }
    }

    public Optional<PlayerSnapshot> load(UUID id) {
        File file = file(id);
        if (!file.isFile()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PlayerSnapshot.load(YamlConfiguration.loadConfiguration(file)));
        } catch (RuntimeException e) {
            log.log(Level.SEVERE, "Could not read the snapshot of " + id + ", keeping the file", e);
            return Optional.empty();
        }
    }

    public boolean has(UUID id) {
        return file(id).isFile();
    }

    public void delete(UUID id) {
        File file = file(id);
        if (file.exists() && !file.delete()) {
            log.warning("Could not delete " + file);
        }
    }

    private File file(UUID id) {
        return new File(directory, id + ".yml");
    }
}
