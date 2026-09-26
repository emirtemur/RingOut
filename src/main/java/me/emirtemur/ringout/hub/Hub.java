package me.emirtemur.ringout.hub;

import me.emirtemur.ringout.config.PluginConfig;
import me.emirtemur.ringout.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Where players are when they are not in a game. The whole server is RingOut, so every player
 * who joins the server or finishes a game is reset and sent here. Persisted in the "hub"
 * section of config.yml.
 */
public final class Hub {

    /** Players with this permission keep their state on server join, so admins can build. */
    public static final String BYPASS_PERMISSION = "ringout.bypass";

    private boolean set;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;

    public static Hub load(PluginConfig.Hub config) {
        Hub hub = new Hub();
        if (config == null) {
            return hub;
        }
        hub.set = config.set;
        hub.world = config.world != null ? config.world : "";
        hub.x = config.x;
        hub.y = config.y;
        hub.z = config.z;
        hub.yaw = config.yaw;
        hub.pitch = config.pitch;
        return hub;
    }

    /** Writes only the location keys; menu-slot and menu in the same section stay as they are. */
    public void save(PluginConfig.Hub config) {
        config.set = set;
        config.world = world;
        config.x = x;
        config.y = y;
        config.z = z;
        config.yaw = yaw;
        config.pitch = pitch;
    }

    public void setLocation(Location location) {
        set = true;
        world = location.getWorld().getName();
        x = location.getX();
        y = location.getY();
        z = location.getZ();
        yaw = location.getYaw();
        pitch = location.getPitch();
    }

    /** The hub point, or the main world's spawn until one is set (or its world is missing). */
    public Location location() {
        if (set) {
            World w = Bukkit.getWorld(world);
            if (w != null) {
                return new Location(w, x, y, z, yaw, pitch);
            }
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Resets the player to the hub state and teleports them to the hub. */
    public void send(Player player) {
        Players.discardPearls(player);
        Players.reset(player, GameMode.ADVENTURE);
        player.teleport(location());
    }
}
