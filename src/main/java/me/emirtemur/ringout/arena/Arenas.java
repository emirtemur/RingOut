package me.emirtemur.ringout.arena;

import eu.okaeri.configs.exception.OkaeriException;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.regex.Pattern;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.config.Configs;
import me.emirtemur.ringout.game.Game;
import me.emirtemur.ringout.util.FailureLog;
import me.emirtemur.ringout.util.SafeYaml;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * All arenas, one file each in plugins/RingOut/arenas/&lt;name&gt;.yml, and the game running on each.
 * Also knows which game every player is in.
 */
public final class Arenas {

    /** Arena names double as file names, so keep them simple. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Blocks kept free between two rings in the same world. */
    public static final int GAP = 10;
    /** Written after the one-time move of the old config.yml arena into arenas/default.yml. */
    private static final String MIGRATED_MARKER = ".migrated";

    private final RingOutPlugin plugin;
    private final File directory;
    private final FailureLog failures;
    private final Map<String, Game> games = new LinkedHashMap<>();
    private final Map<UUID, Game> playerGames = new HashMap<>();

    public Arenas(RingOutPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "arenas");
        this.failures = new FailureLog(plugin.getLogger());
    }

    // --- Loading -----------------------------------------------------------

    /**
     * (Re)reads every arena file. Only call while no game has players. A file that does not parse
     * is skipped with an error, so one broken arena never takes the others down. The config is
     * null when config.yml failed to load; the one-time migration then waits for a good start.
     */
    public void load(FileConfiguration config) {
        games.values().forEach(Game::shutdown);
        games.clear();
        playerGames.clear();
        failures.clearAll();
        if (config != null) {
            migrateLegacyArena(config);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                plugin.getLogger().warning("Could not create " + directory);
            }
        }

        File[] files = directory.listFiles((dir, file) -> file.endsWith(".yml"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            if (!NAME.matcher(name).matches()) {
                plugin.getLogger().warning("Skipping " + file.getName() + ": arena names may only use a-z, 0-9, _ and -.");
                continue;
            }
            ArenaConfig arenaConfig;
            try {
                arenaConfig = Configs.read(ArenaConfig.class, file);
            } catch (IOException | OkaeriException e) {
                plugin.getLogger().severe("Arena " + name + " is not loaded because " + file.getName()
                        + " has an error: " + Configs.describe(e));
                continue;
            }
            // The file parsed, so it is safe to write back with any keys it was missing.
            try {
                Configs.writeIfChanged(arenaConfig, file);
            } catch (IOException | OkaeriException e) {
                plugin.getLogger().log(Level.WARNING, "Could not add missing keys to " + file.getName(), e);
            }
            Arena arena = Arena.load(name, arenaConfig, plugin.getLogger());
            games.put(name, new Game(plugin, arena));
        }
        warnAboutOverlaps();
    }

    /** Commands refuse overlapping rings, but hand-edited files are only checked here. */
    private void warnAboutOverlaps() {
        List<Arena> loaded = games.values().stream().map(Game::arena).toList();
        for (int i = 0; i < loaded.size(); i++) {
            Arena a = loaded.get(i);
            for (int j = i + 1; j < loaded.size(); j++) {
                Arena b = loaded.get(j);
                if (a.touches(b.worldName(), b.centerX(), b.centerZ(), Math.max(b.radius(), b.builtRadius()), GAP)) {
                    plugin.getLogger().warning("Arenas " + a.name() + " and " + b.name()
                            + " are closer than " + GAP + " blocks; players and explosions may reach the other ring.");
                }
            }
        }
    }

    /**
     * Versions before multi-arena kept the one arena in the "arena" section of config.yml. It becomes
     * arenas/default.yml once: the marker file is only written after a successful move, so a failed
     * move is retried on the next start, and deleting every arena later does not bring it back.
     */
    private void migrateLegacyArena(FileConfiguration config) {
        File marker = new File(directory, MIGRATED_MARKER);
        File[] existing = directory.listFiles((dir, file) -> file.endsWith(".yml"));
        // isSet ignores the jar defaults, which no longer have an arena section anyway.
        if (marker.exists() || (existing != null && existing.length > 0)
                || !config.isSet("arena") || !config.isConfigurationSection("arena")) {
            return;
        }
        ConfigurationSection legacy = config.getConfigurationSection("arena");
        try {
            SafeYaml.update(new File(directory, "default.yml"), null, root -> {
                for (String key : legacy.getKeys(true)) {
                    // blocks-per-tick is a game setting now, not part of an arena.
                    if (!legacy.isConfigurationSection(key) && !key.equals("blocks-per-tick")) {
                        root.set(key, legacy.get(key));
                    }
                }
            });
            SafeYaml.writeAtomically(marker, "The arena section of config.yml was moved to default.yml.\n");
        } catch (SafeYaml.SaveException e) {
            plugin.getLogger().log(e.level(), "Could not move the arena from config.yml: " + e.getMessage(), e.getCause());
            return;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Moved the arena, but could not write " + marker, e);
        }
        plugin.getLogger().info("Moved the arena from config.yml to arenas/default.yml. "
                + "The arena section in config.yml is no longer used and can be removed.");
    }

    // --- Lookup ------------------------------------------------------------

    public Collection<Game> games() {
        return games.values();
    }

    public List<String> names() {
        return List.copyOf(games.keySet());
    }

    public Game game(String name) {
        return games.get(name.toLowerCase(Locale.ROOT));
    }

    /** The game the player is in (lobby, playing or spectating), or null when they are in the hub. */
    public Game gameOf(Player player) {
        return playerGames.get(player.getUniqueId());
    }

    public void track(Player player, Game game) {
        playerGames.put(player.getUniqueId(), game);
    }

    public void untrack(Player player) {
        untrack(player.getUniqueId());
    }

    public void untrack(UUID id) {
        playerGames.remove(id);
    }

    /** Whether the block belongs to any configured ring. */
    public boolean isArenaBlock(Block block) {
        for (Game game : games.values()) {
            if (game.arena().isArenaBlock(block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Another arena (not {@code self}) that a ring at this spot would touch, or null. Checked
     * before a change is applied, so a refused change leaves the arena as it was.
     */
    public Arena overlapping(Arena self, String world, int x, int z, int radius) {
        for (Game game : games.values()) {
            Arena other = game.arena();
            if (other != self && other.touches(world, x, z, radius, GAP)) {
                return other;
            }
        }
        return null;
    }

    /** True when no game has players and no ring is being built, so arenas can be reloaded. */
    public boolean allIdle() {
        return playerGames.isEmpty() && !plugin.arenaBuilder().isBusy()
                && games.values().stream().allMatch(Game::isIdle);
    }

    // --- Changes -----------------------------------------------------------

    /** Creates and saves a new arena. Returns null if its file could not be written. */
    public Game create(Arena arena) {
        File file = file(arena.name());
        if (file.exists()) {
            // Most likely a file that failed to load; never overwrite it with a fresh arena.
            plugin.getLogger().warning("Not creating arena " + arena.name() + ": " + file.getName() + " already exists.");
            return null;
        }
        ArenaConfig config = Configs.create(ArenaConfig.class);
        arena.save(config);
        arena.saveAppearance(config);
        try {
            Configs.write(config, file);
        } catch (IOException | OkaeriException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file.getName(), e);
            return null;
        }
        arena.markSaved();
        Game game = new Game(plugin, arena);
        games.put(arena.name(), game);
        return game;
    }

    /** Forgets the arena and deletes its file; the ring's blocks stay in the world. */
    public boolean delete(Game game) {
        File file = file(game.arena().name());
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("Could not delete " + file);
            return false;
        }
        game.shutdown();
        games.remove(game.arena().name());
        failures.clear(game.arena().name());
        return true;
    }

    /**
     * Writes the arena's settings into its file as it is on disk right now, so edits made to the
     * file since the last reload are kept. Returns false when the change was not saved.
     */
    public boolean save(Arena arena) {
        if (!update(arena, arena::save)) {
            return false;
        }
        arena.markSaved();
        return true;
    }

    /**
     * Saves only the built/built-radius keys after a build, so settings edited in the file are not
     * overwritten by a build the admin did not start. If an admin change (center, radius...) is
     * still unsaved, the whole arena is written instead, so the file never pairs an old center
     * with the new ring's build state. A failed save is retried after the next build.
     */
    public void saveBuildState(Arena arena) {
        if (arena.isSettingsDirty()) {
            save(arena);
        } else if (arena.isBuildStateDirty() && update(arena, arena::saveBuildState)) {
            arena.markSaved();
        }
    }

    /**
     * Re-reads the arena file as it is on disk right now, applies only this change and writes it
     * atomically, so edits made to the file since the last reload are kept. Nothing is written
     * when the file does not parse at this moment.
     */
    private boolean update(Arena arena, Consumer<ArenaConfig> writer) {
        File file = file(arena.name());
        ArenaConfig config;
        try {
            config = Configs.read(ArenaConfig.class, file);
        } catch (IOException | OkaeriException e) {
            failures.fail(arena.name(), Level.WARNING, "Arena " + arena.name() + ": " + file.getName()
                    + " currently has an error, changes are not saved: " + Configs.describe(e), null);
            return false;
        }
        writer.accept(config);
        try {
            Configs.write(config, file);
        } catch (IOException | OkaeriException e) {
            failures.fail(arena.name(), Level.SEVERE, "Arena " + arena.name() + ": could not save " + file.getName(), e);
            return false;
        }
        failures.clear(arena.name());
        return true;
    }

    /** Whether arenas/&lt;name&gt;.yml exists, loaded or not (a broken file is skipped on load). */
    public boolean hasFile(String name) {
        return file(name).exists();
    }

    private File file(String name) {
        return new File(directory, name + ".yml");
    }

    /** Plugin disable: every game sends its players to the hub right away. */
    public void shutdown() {
        games.values().forEach(Game::shutdown);
        playerGames.clear();
    }
}
