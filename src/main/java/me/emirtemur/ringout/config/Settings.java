package me.emirtemur.ringout.config;

import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;

/** Typed, immutable view of config.yml (except the arena section, see Arena). */
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

    public final boolean suddenDeathEnabled;
    public final int suddenDeathStartAfter;
    public final int shrinkInterval;
    public final int minRadius;

    public final List<String> winCommands;

    private final FileConfiguration config;
    private final String prefix;

    public Settings(FileConfiguration config) {
        this.config = config;
        minPlayers = Math.max(1, config.getInt("game.min-players", 2));
        maxPlayers = Math.max(minPlayers, config.getInt("game.max-players", 16));
        autoStart = config.getBoolean("game.auto-start", true);
        lobbyWait = Math.max(0, config.getInt("game.lobby-wait", 15));
        countdown = Math.max(1, config.getInt("game.countdown", 5));
        itemInterval = Math.max(1, config.getInt("game.item-interval", 8));
        margin = Math.max(0, config.getDouble("game.margin", 0.5));
        outsideGraceTicks = Math.max(0, config.getInt("game.outside-grace-ticks", 20));
        fallDepth = Math.max(1, config.getInt("game.fall-depth", 5));
        pvpDamage = config.getBoolean("game.pvp-damage", true);
        explosionsBreakArena = config.getBoolean("game.explosions-break-arena", true);
        autoPrimeTnt = config.getBoolean("game.auto-prime-tnt", true);
        tntFuseTicks = Math.max(1, config.getInt("game.tnt-fuse-ticks", 40));
        spectatorMaxDistance = Math.max(0, config.getInt("game.spectator-max-distance", 0));
        endingSeconds = Math.max(1, config.getInt("game.ending-seconds", 8));
        blocksPerTick = Math.max(100, config.getInt("arena.blocks-per-tick", 2000));

        suddenDeathEnabled = config.getBoolean("sudden-death.enabled", true);
        suddenDeathStartAfter = Math.max(0, config.getInt("sudden-death.start-after", 300));
        shrinkInterval = Math.max(1, config.getInt("sudden-death.shrink-interval", 5));
        minRadius = Math.max(1, config.getInt("sudden-death.min-radius", 3));

        winCommands = List.copyOf(config.getStringList("win-commands"));
        prefix = Objects.requireNonNullElse(config.getString("messages.prefix"), "");
    }

    /** A chat message with the prefix. */
    public Component message(String key, TagResolver... resolvers) {
        return MINI.deserialize(prefix + rawString(key), resolvers);
    }

    /** A message without the prefix, for titles, boss bars and item names. */
    public Component text(String key, TagResolver... resolvers) {
        return MINI.deserialize(rawString(key), resolvers);
    }

    private String rawString(String key) {
        // No explicit default here, so keys missing from an older config.yml fall back to the jar's copy.
        String value = config.getString("messages." + key);
        return value != null ? value : key;
    }

    public static MiniMessage mini() {
        return MINI;
    }
}
