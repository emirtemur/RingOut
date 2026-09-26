package me.emirtemur.ringout;

import eu.okaeri.configs.exception.OkaeriException;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import me.emirtemur.ringout.arena.ArenaBuilder;
import me.emirtemur.ringout.arena.Arenas;
import me.emirtemur.ringout.arena.VoidGenerator;
import me.emirtemur.ringout.command.RingOutCommand;
import me.emirtemur.ringout.config.Configs;
import me.emirtemur.ringout.config.Messages;
import me.emirtemur.ringout.config.PluginConfig;
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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

public final class RingOutPlugin extends JavaPlugin {

    /** Void worlds spawn players just above the default ring height. */
    private static final int VOID_SPAWN_Y = 151;

    /**
     * Texts whose default changed after they were last kept in config.yml (1.0.4). When moving the
     * messages out, a value still equal to its old default is dropped so the new default applies.
     */
    private static final Map<String, String> OUTDATED_DEFAULTS = Map.of(
            "reload-failed", "<red>config.yml has an error, the previous configuration is kept and hub changes "
                    + "are not saved. <count> arena(s) reloaded. Check the console.");

    private PluginConfig pluginConfig;
    private Messages messages;
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
        arenas.load(configSavable ? rawConfig() : null);
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
     * (Re)reads config.yml and messages.yml through Okaeri. Only call while no game is running.
     * A file that parses is written back with any keys it was missing (new settings or texts after
     * an update); a file that does not parse is never written and its previous version stays
     * active (the defaults on startup). Returns false when either file could not be loaded.
     */
    public boolean loadConfiguration() {
        boolean configLoaded = loadPluginConfig();
        boolean messagesLoaded = loadMessages(configLoaded);
        settings = new Settings(pluginConfig, messages);
        return configLoaded && messagesLoaded;
    }

    /**
     * config.yml. The item pool and hub are only rebuilt when it loads: after a failed reload the
     * hub keeps the location set in this session. Hub changes are not saved until it loads again.
     */
    private boolean loadPluginConfig() {
        File file = configFile();
        PluginConfig loaded;
        try {
            loaded = Configs.read(PluginConfig.class, file);
        } catch (IOException | OkaeriException e) {
            getLogger().severe("config.yml could not be loaded, hub changes will not be saved until it is fixed: "
                    + Configs.describe(e));
            configSavable = false;
            if (pluginConfig == null) {
                usePluginConfig(Configs.create(PluginConfig.class));
            }
            return false;
        }
        try {
            Configs.writeIfChanged(loaded, file);
        } catch (IOException | OkaeriException e) {
            getLogger().log(Level.WARNING, "Could not add missing keys to config.yml", e);
        }
        configSavable = true;
        failures.clear("config");
        usePluginConfig(loaded);
        return true;
    }

    private void usePluginConfig(PluginConfig config) {
        pluginConfig = config;
        itemPool = ItemPool.load(config.items, getLogger());
        hub = Hub.load(config.hub);
    }

    /**
     * messages.yml. On the first start after messages moved out of config.yml it is created from
     * the old messages section there, so edited texts are kept. That waits for a config.yml that
     * loads, so a broken config.yml never leads to a messages.yml made of defaults only.
     */
    private boolean loadMessages(boolean configLoaded) {
        File file = messagesFile();
        Messages loaded;
        boolean fromOldConfig = false;
        try {
            if (!file.exists()) {
                if (!configLoaded) {
                    messages = messages != null ? messages : Configs.create(Messages.class);
                    return true;
                }
                loaded = Configs.create(Messages.class);
                ConfigurationSection old = rawConfig().getConfigurationSection("messages");
                if (old != null) {
                    Map<String, Object> texts = new LinkedHashMap<>(old.getValues(false));
                    // An old default that was never edited should become the new default, not stay.
                    OUTDATED_DEFAULTS.forEach((key, oldDefault) -> texts.remove(key, oldDefault));
                    loaded.load(texts);
                    fromOldConfig = true;
                }
            } else {
                loaded = Configs.read(Messages.class, file);
            }
        } catch (IOException | OkaeriException e) {
            getLogger().severe("messages.yml could not be loaded, the previous texts are kept until it is fixed: "
                    + Configs.describe(e));
            if (messages == null) {
                messages = Configs.create(Messages.class);
            }
            return false;
        }
        boolean written = true;
        try {
            Configs.writeIfChanged(loaded, file);
        } catch (IOException | OkaeriException e) {
            written = false;
            getLogger().log(Level.WARNING, "Could not write messages.yml", e);
        }
        if (fromOldConfig && written) {
            removeOldMessages();
        }
        messages = loaded;
        return true;
    }

    /**
     * Drops the messages section from config.yml once messages.yml holds it, so deleting
     * messages.yml later gives the defaults instead of moving the old texts back.
     */
    private void removeOldMessages() {
        try {
            SafeYaml.update(configFile(), null, root -> root.set("messages", null));
            getLogger().info("Moved the messages from config.yml to messages.yml. Texts you had not changed "
                    + "keep their old wording; delete a line in messages.yml to get the new default text.");
        } catch (SafeYaml.SaveException e) {
            getLogger().log(e.level(), "Moved the messages to messages.yml, but could not remove them from "
                    + "config.yml: " + e.getMessage(), e.getCause());
        }
    }

    /** Reloads config.yml, messages.yml, every arena file and the menus. Only call while all arenas are idle. */
    public boolean reloadAll() {
        boolean configLoaded = loadConfiguration();
        arenas.load(configSavable ? rawConfig() : null);
        loadArenaWorlds();
        menus.closeAll();
        menus.load();
        return configLoaded;
    }

    /**
     * config.yml as plain YAML, for the one-time move of the old "arena" section (a key the
     * current config no longer has, kept in the file because orphans are not removed).
     */
    private YamlConfiguration rawConfig() {
        return YamlConfiguration.loadConfiguration(configFile());
    }

    /**
     * Writes only the hub location into config.yml as it is on disk right now, so edits made to
     * the file since the last reload are kept. Nothing is written if the last load failed or the
     * file does not parse at this moment. Returns false when the change was not saved.
     */
    public boolean saveHub() {
        if (!configSavable) {
            failures.fail("config", Level.WARNING,
                    "config.yml failed to load, so hub changes are not saved.", null);
            return false;
        }
        File file = configFile();
        PluginConfig onDisk;
        try {
            onDisk = Configs.read(PluginConfig.class, file);
        } catch (IOException | OkaeriException e) {
            failures.fail("config", Level.WARNING,
                    "config.yml currently has an error, hub changes are not saved: " + Configs.describe(e), null);
            return false;
        }
        hub.save(onDisk.hub);
        try {
            Configs.write(onDisk, file);
        } catch (IOException | OkaeriException e) {
            failures.fail("config", Level.SEVERE, "Could not save config.yml", e);
            return false;
        }
        failures.clear("config");
        return true;
    }

    private File configFile() {
        return new File(getDataFolder(), "config.yml");
    }

    private File messagesFile() {
        return new File(getDataFolder(), "messages.yml");
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
