package me.emirtemur.ringout;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Level;
import me.emirtemur.ringout.arena.ArenaBuilder;
import me.emirtemur.ringout.arena.Arenas;
import me.emirtemur.ringout.arena.VoidGenerator;
import me.emirtemur.ringout.command.RingOutCommand;
import me.emirtemur.ringout.config.Settings;
import me.emirtemur.ringout.game.Game;
import me.emirtemur.ringout.game.GameListener;
import me.emirtemur.ringout.hub.Hub;
import me.emirtemur.ringout.hub.HubListener;
import me.emirtemur.ringout.item.ItemPool;
import me.emirtemur.ringout.menu.MenuItem;
import me.emirtemur.ringout.menu.MenuListener;
import me.emirtemur.ringout.menu.Menus;
import me.emirtemur.ringout.util.FailureLog;
import me.emirtemur.ringout.util.SafeYaml;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class RingOutPlugin extends JavaPlugin {

    /** Void worlds spawn players just above the default ring height. */
    private static final int VOID_SPAWN_Y = 151;

    private Settings settings;
    /** False when the last load of config.yml failed, so the in-memory hub did not come from the file. */
    private boolean configSavable;
    private FailureLog failures;
    private ItemPool itemPool;
    private Hub hub;
    private ArenaBuilder arenaBuilder;
    private Arenas arenas;
    private MenuItem menuItem;
    private Menus menus;

    @Override
    public void onEnable() {
        failures = new FailureLog(getLogger());
        loadConfiguration();
        arenaBuilder = new ArenaBuilder(this);
        arenas = new Arenas(this);
        arenas.load(configSavable ? getConfig() : null);
        loadArenaWorlds();
        menus = new Menus(this);
        menus.load();

        menuItem = new MenuItem(this);
        MenuListener menuListener = new MenuListener(this);
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        getServer().getPluginManager().registerEvents(new HubListener(this), this);
        getServer().getPluginManager().registerEvents(menuListener, this);
        // Open menus redraw on their update interval, so states and player counts stay live.
        getServer().getScheduler().runTaskTimer(this, menuListener::tickOpenMenus, 20L, 20L);
        RingOutCommand command = new RingOutCommand(this);
        Objects.requireNonNull(getCommand("ringout")).setExecutor(command);
        Objects.requireNonNull(getCommand("ringout")).setTabCompleter(command);

        // After a /reload, players already online are put in the hub like on a fresh join.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(Hub.BYPASS_PERMISSION)) {
                sendToHub(player);
            }
        }
        warnAboutOldSnapshots();
        getLogger().info("RingOut enabled with " + arenas.games().size() + " arena(s) and "
                + itemPool.size() + " item pool entries.");
    }

    /** Older versions saved inventories there; they are left alone but no longer restored. */
    private void warnAboutOldSnapshots() {
        File[] files = new File(getDataFolder(), "snapshots").listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null && files.length > 0) {
            getLogger().warning(files.length + " snapshots from an older RingOut version remain in "
                    + "plugins/RingOut/snapshots and are no longer restored.");
        }
    }

    @Override
    public void onDisable() {
        if (arenas != null) {
            arenas.shutdown();
        }
        if (arenaBuilder != null) {
            arenaBuilder.cancelAll();
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        return new VoidGenerator(VOID_SPAWN_Y);
    }

    /**
     * (Re)reads config.yml. Only call while no game is running. If the file cannot be parsed,
     * the previous configuration stays active (jar defaults on startup) and hub changes are
     * not saved until it loads again. Returns false when the file could not be parsed.
     */
    public boolean loadConfiguration() {
        saveDefaultConfig();
        try {
            new YamlConfiguration().load(configFile());
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("config.yml could not be parsed, hub changes will not be saved until it is fixed: "
                    + e.getMessage());
            configSavable = false;
            if (settings == null) {
                // First load: run on the jar's config.yml. Not via getConfig(), which would parse
                // the broken file again and log a second stack trace.
                applyConfiguration(jarDefaults());
            }
            return false;
        }
        reloadConfig();
        configSavable = true;
        failures.clear("config");
        applyConfiguration(getConfig());
        return true;
    }

    /** Reloads config.yml and every arena file. Only call while all arenas are idle. */
    public boolean reloadAll() {
        boolean configLoaded = loadConfiguration();
        arenas.load(configSavable ? getConfig() : null);
        loadArenaWorlds();
        menus.closeAll();
        menus.load();
        return configLoaded;
    }

    private void applyConfiguration(FileConfiguration config) {
        settings = new Settings(config);
        itemPool = ItemPool.load(config.getMapList("items"), getLogger());
        hub = Hub.load(config.getConfigurationSection("hub"));
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
     * Writes only the hub section into config.yml as it is on disk right now, so edits made to
     * the file since the last reload are kept. Nothing is written if the last load failed or the
     * file does not parse at this moment. Returns false when the change was not saved.
     */
    public boolean saveHub() {
        if (!configSavable) {
            failures.fail("config", Level.WARNING,
                    "config.yml failed to load, so hub changes are not saved.", null);
            return false;
        }
        try {
            SafeYaml.update(configFile(), "hub", hub::save);
        } catch (SafeYaml.SaveException e) {
            failures.fail("config", e.level(), e.getMessage(), e.getCause());
            return false;
        }
        failures.clear("config");
        return true;
    }

    private File configFile() {
        return new File(getDataFolder(), "config.yml");
    }

    /** Loads each arena's world on startup if it exists on disk but is not loaded yet. */
    private void loadArenaWorlds() {
        for (Game game : arenas.games()) {
            String name = game.arena().worldName();
            if (Bukkit.getWorld(name) == null && new File(Bukkit.getWorldContainer(), name).isDirectory()) {
                createVoidWorld(name);
            }
        }
    }

    /**
     * Creates (or loads) an empty void world for rings. Returns null for an already loaded world
     * that is not a void world, so a typo can't change the rules of the main world. Game rules are
     * only set when the world is new; an existing void world keeps what the admin set.
     */
    public World createVoidWorld(String name) {
        World loaded = Bukkit.getWorld(name);
        if (loaded != null) {
            return loaded.getGenerator() instanceof VoidGenerator ? loaded : null;
        }
        boolean isNew = !new File(Bukkit.getWorldContainer(), name).isDirectory();
        World world = new WorldCreator(name)
                .generator(new VoidGenerator(VOID_SPAWN_Y))
                .generateStructures(false)
                .createWorld();
        if (world != null && isNew) {
            world.setGameRule(GameRules.ADVANCE_TIME, false);
            world.setGameRule(GameRules.ADVANCE_WEATHER, false);
            world.setGameRule(GameRules.SPAWN_MOBS, false);
            // 0 = fire never spreads (replaces the old doFireTick=false).
            world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
            world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
            world.setTime(6000);
            world.setStorm(false);
            world.setSpawnLocation(0, VOID_SPAWN_Y, 0);
        }
        return world;
    }

    /** False when the last config.yml load failed; hub changes then can't be saved until a successful reload. */
    public boolean isConfigSavable() {
        return configSavable;
    }

    public Settings settings() {
        return settings;
    }

    public ItemPool itemPool() {
        return itemPool;
    }

    public ArenaBuilder arenaBuilder() {
        return arenaBuilder;
    }

    public Arenas arenas() {
        return arenas;
    }

    public Hub hub() {
        return hub;
    }

    public MenuItem menuItem() {
        return menuItem;
    }

    public Menus menus() {
        return menus;
    }

    /** Resets the player to the hub state, teleports them to the hub and hands out the arena menu compass. */
    public void sendToHub(Player player) {
        hub.send(player);
        player.getInventory().setItem(settings.menuSlot, menuItem.create(settings));
    }
}
