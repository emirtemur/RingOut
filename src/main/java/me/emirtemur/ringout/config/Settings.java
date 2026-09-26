package me.emirtemur.ringout.config;

import eu.okaeri.configs.exception.OkaeriException;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** Checked, immutable values from config.yml and the texts of messages.yml (the hub lives in Hub, arenas in their own files). */
public final class Settings {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public final int minPlayers;
    public final int maxPlayers;
    public final boolean autoStart;
    public final int lobbyWait;
    public final int countdown;
    public final int itemInterval;
    public final double margin;
    public final int outsideGraceTicks;
    public final int fallDepth;
    public final boolean pvpDamage;
    public final boolean explosionsBreakArena;
    public final boolean autoPrimeTnt;
    public final int tntFuseTicks;
    public final int spectatorMaxDistance;
    public final int endingSeconds;
    public final int blocksPerTick;
    /** Hotbar slot (0-8) of the arena menu compass in the hub. */
    public final int menuSlot;
    /** The menu (menus/<name>.yml) the hub compass opens. */
    public final String hubMenu;

    public final boolean suddenDeathEnabled;
    public final int suddenDeathStartAfter;
    public final int shrinkInterval;
    public final int minRadius;

    public final List<String> winCommands;
    /** Radius, slices and look for arenas created with /ro create. */
    public final PluginConfig.ArenaDefaults arenaDefaults;

    private final Messages messages;
    private final String prefix;

    public Settings(PluginConfig config, Messages messages) {
        PluginConfig.Game game = config.game;
        minPlayers = Math.max(1, game.minPlayers);
        maxPlayers = Math.max(minPlayers, game.maxPlayers);
        autoStart = game.autoStart;
        lobbyWait = Math.max(0, game.lobbyWait);
        countdown = Math.max(1, game.countdown);
        itemInterval = Math.max(1, game.itemInterval);
        margin = Math.max(0, game.margin);
        outsideGraceTicks = Math.max(0, game.outsideGraceTicks);
        fallDepth = Math.max(1, game.fallDepth);
        pvpDamage = game.pvpDamage;
        explosionsBreakArena = game.explosionsBreakArena;
        autoPrimeTnt = game.autoPrimeTnt;
        tntFuseTicks = Math.max(1, game.tntFuseTicks);
        spectatorMaxDistance = Math.max(0, game.spectatorMaxDistance);
        endingSeconds = Math.max(1, game.endingSeconds);
        blocksPerTick = Math.max(100, game.blocksPerTick);
        menuSlot = Math.max(0, Math.min(8, config.hub.menuSlot));
        hubMenu = Objects.requireNonNullElse(config.hub.menu, "arenas");

        suddenDeathEnabled = config.suddenDeath.enabled;
        suddenDeathStartAfter = Math.max(0, config.suddenDeath.startAfter);
        shrinkInterval = Math.max(1, config.suddenDeath.shrinkInterval);
        minRadius = Math.max(1, config.suddenDeath.minRadius);

        winCommands = config.winCommands != null ? List.copyOf(config.winCommands) : List.of();
        arenaDefaults = config.arenaDefaults;
        this.messages = messages;
        prefix = Objects.requireNonNullElse(messages.prefix, "");
    }

    /** A chat message with the prefix. */
    public Component message(String key, TagResolver... resolvers) {
        return MINI.deserialize(prefix + rawString(key), resolvers);
    }

    /** A message without the prefix, for titles, boss bars and item names. */
    public Component text(String key, TagResolver... resolvers) {
        return MINI.deserialize(rawString(key), resolvers);
    }

    /** The unparsed MiniMessage text of a message, for placing inside other texts (menus). */
    public String raw(String key) {
        return rawString(key);
    }

    /** Looks the message up by its key in the file (e.g. "arena-not-found"); unknown keys show as themselves. */
    private String rawString(String key) {
        try {
            Object value = messages.get(key);
            return value != null ? value.toString() : key;
        } catch (OkaeriException e) {
            return key;
        }
    }

    public static MiniMessage mini() {
        return MINI;
    }
}
