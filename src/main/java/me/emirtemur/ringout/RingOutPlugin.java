package me.emirtemur.ringout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import me.emirtemur.ringout.arena.Arena;
import me.emirtemur.ringout.arena.ArenaBuilder;
import me.emirtemur.ringout.arena.VoidGenerator;
import me.emirtemur.ringout.command.RingOutCommand;
import me.emirtemur.ringout.config.Settings;
import me.emirtemur.ringout.game.Game;
import me.emirtemur.ringout.game.GameListener;
import me.emirtemur.ringout.game.SnapshotStore;
import me.emirtemur.ringout.item.ItemPool;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class RingOutPlugin extends JavaPlugin {

    private Settings settings;
    /** False when the last load of config.yml failed, so the in-memory arena did not come from the file. */
    private boolean arenaSavable;
    /** config.yml failed to load at startup, so the arena is the jar default, not the server's arena. */
    private boolean arenaFromJar;
    /** Highest level a failed arena save was logged at; lower or equal repeats go to FINE. Null = none yet. */
    private Level unsavedLogged;
    private Arena arena;
    private ItemPool itemPool;
    private ArenaBuilder arenaBuilder;
    private SnapshotStore snapshots;
    private Game game;

    @Override
    public void onEnable() {
        loadConfiguration();
        arenaBuilder = new ArenaBuilder(this);
        snapshots = new SnapshotStore(new File(getDataFolder(), "snapshots"), getLogger());
        game = new Game(this);
        loadArenaWorld();

        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        RingOutCommand command = new RingOutCommand(this);
        Objects.requireNonNull(getCommand("ringout")).setExecutor(command);
        Objects.requireNonNull(getCommand("ringout")).setTabCompleter(command);

        // After a /reload, players online may still have a snapshot from an interrupted game.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (snapshots.has(player.getUniqueId())) {
                restoreLeftover(player);
            }
        }
        getLogger().info("RingOut enabled with " + itemPool.size() + " item pool entries.");
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
        if (arenaBuilder != null) {
            arenaBuilder.cancelAll();
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new VoidGenerator(arena != null ? arena.centerY() + 1 : 151);
    }

    /**
     * (Re)reads config.yml. Only call while no game is running. If the file cannot be parsed,
     * the previous configuration stays active (jar defaults on startup) and arena changes are
     * not saved until it loads again. Returns false when the file could not be parsed.
     */
    public boolean loadConfiguration() {
        saveDefaultConfig();
        try {
            new YamlConfiguration().load(configFile());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml could not be parsed, arena changes will not be saved until it is fixed: "
                    + e.getMessage());
            arenaSavable = false;
            if (settings == null) {
                // First load: run on the jar's config.yml. Not via getConfig(), which would parse
                // the broken file again and log a second stack trace.
                applyConfiguration(jarDefaults());
                arenaFromJar = true;
            }
            return false;
        }
        reloadConfig();
        arenaSavable = true;
        arenaFromJar = false;
        unsavedLogged = null;
        applyConfiguration(getConfig());
        return true;
    }

    private void applyConfiguration(FileConfiguration config) {
        settings = new Settings(config);
        ConfigurationSection section = config.getConfigurationSection("arena");
        arena = Arena.load(section != null ? section : config.createSection("arena"), getLogger());
        itemPool = ItemPool.load(config.getMapList("items"), getLogger());
    }

    private YamlConfiguration jarDefaults() {
        InputStream stream = Objects.requireNonNull(getResource("config.yml"), "config.yml missing from the jar");
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the default config.yml", e);
        }
    }

    /**
     * Writes only the arena section into config.yml as it is on disk right now, so edits made to
     * the file since the last reload are kept. Nothing is written if the arena was not loaded from
     * the file (it failed to parse) or if the file does not parse at this moment.
     * Returns false when the change was not saved.
     */
    public boolean saveArena() {
        if (!updateArenaSection(arena::save)) {
            return false;
        }
        arena.markSaved();
        return true;
    }

    /**
     * Saves only the built/built-radius keys after a build, so arena settings edited on disk
     * since the last reload are not overwritten by a build the admin did not start.
     * If an admin change (center, radius...) is still unsaved, the whole arena is written instead,
     * so the file never pairs an old center with the new ring's build state.
     * A failed save is retried after the next build.
     */
    public void saveBuildState() {
        if (arena.isSettingsDirty()) {
            saveArena();
        } else if (arena.isBuildStateDirty() && updateArenaSection(arena::saveBuildState)) {
            arena.markSaved();
        }
    }

    private boolean updateArenaSection(Consumer<ConfigurationSection> writer) {
        if (!arenaSavable) {
            return unsaved(Level.WARNING, "config.yml failed to load, so arena changes are not saved.", null);
        }
        File file = configFile();
        YamlConfiguration disk = new YamlConfiguration();
        try {
            disk.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            return unsaved(Level.WARNING, "config.yml currently has an error, arena changes are not saved: "
                    + e.getMessage(), null);
        }
        if (disk.isSet("arena") && !disk.isConfigurationSection("arena")) {
            return unsaved(Level.WARNING, "'arena' in config.yml is not a section, arena changes are not saved.", null);
        }
        ConfigurationSection section = disk.isConfigurationSection("arena")
                ? disk.getConfigurationSection("arena")
                : disk.createSection("arena");
        writer.accept(section);
        try {
            writeAtomically(file, disk.saveToString());
        } catch (IOException e) {
            return unsaved(Level.SEVERE, "Could not save config.yml", e);
        }
        unsavedLogged = null;
        return true;
    }

    /**
     * Logs a failed arena save. Builds retry the save every game, so a failure is logged at its level
     * only if nothing as severe was logged yet (a new SEVERE after a WARNING still shows); repeats go
     * to FINE until a save or reload succeeds. Returns false.
     */
    private boolean unsaved(Level level, String message, Throwable error) {
        boolean escalated = unsavedLogged == null || level.intValue() > unsavedLogged.intValue();
        getLogger().log(escalated ? level : Level.FINE, message, error);
        if (escalated) {
            unsavedLogged = level;
        }
        return false;
    }

    /**
     * Writes and syncs a temp file first, then moves it over config.yml, so neither a crash nor a
     * power loss mid-write can leave a half-written or empty config.yml. The temp file is removed on failure.
     */
    private static void writeAtomically(File file, String content) throws IOException {
        Path target = file.toPath();
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

    private File configFile() {
        return new File(getDataFolder(), "config.yml");
    }

    /** Loads the arena world on startup if it exists on disk but is not loaded yet. */
    private void loadArenaWorld() {
        String name = arena.worldName();
        if (Bukkit.getWorld(name) == null && new File(Bukkit.getWorldContainer(), name).isDirectory()) {
            createVoidWorld(name);
        }
    }

    /** Creates (or loads) an empty void world for the ring. */
    public World createVoidWorld(String name) {
        World world = new WorldCreator(name)
                .generator(new VoidGenerator(arena.centerY() + 1))
                .generateStructures(false)
                .createWorld();
        if (world != null) {
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setGameRule(GameRules.ADVANCE_WEATHER, false);
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            // 0 = fire never spreads (replaces the old doFireTick=false).
            world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
            world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
            world.setTime(6000);
            world.setStorm(false);
            world.setSpawnLocation(arena.centerX(), arena.centerY() + 1, arena.centerZ());
        }
        return world;
    }

    /** Gives a player back what they had before an interrupted game (crash, restart, quit). */
    public void restoreLeftover(Player player) {
        if (!player.isOnline() || game.isPlaying(player)) {
            return;
        }
        snapshots.load(player.getUniqueId()).ifPresent(snapshot -> {
            // After a crash the last auto-save may have stored an in-flight arena pearl with the player.
            Game.discardPearls(player);
            snapshot.restore(player);
            snapshots.delete(player.getUniqueId());
            player.sendMessage(settings.message("snapshot-restored"));
        });
    }

    /** False when the last config.yml load failed; arena changes then can't be saved until a successful reload. */
    public boolean isArenaSavable() {
        return arenaSavable;
    }

    /** True while the arena comes from the jar defaults because config.yml failed to load at startup. */
    public boolean isArenaFromJar() {
        return arenaFromJar;
    }

    public Settings settings() {
        return settings;
    }

    public Arena arena() {
        return arena;
    }

    public ItemPool itemPool() {
        return itemPool;
    }

    public ArenaBuilder arenaBuilder() {
        return arenaBuilder;
    }

    public SnapshotStore snapshots() {
        return snapshots;
    }

    public Game game() {
        return game;
    }
}
