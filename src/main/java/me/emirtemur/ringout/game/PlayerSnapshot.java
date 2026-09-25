package me.emirtemur.ringout.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/** Everything about a player that a game changes, so it can be put back afterwards. */
public record PlayerSnapshot(
        ItemStack[] contents,
        String world,
        double x, double y, double z, float yaw, float pitch,
        GameMode gameMode,
        double health,
        int foodLevel,
        float saturation,
        int level,
        float exp,
        int fireTicks,
        List<PotionEffect> effects,
        boolean allowFlight,
        boolean flying) {

    public static PlayerSnapshot capture(Player player) {
        Location loc = player.getLocation();
        return new PlayerSnapshot(
                // getContents() returns mirrors of the live stacks, so copy each one.
                Arrays.stream(player.getInventory().getContents())
                        .map(item -> item == null ? null : item.clone())
                        .toArray(ItemStack[]::new),
                loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(),
                player.getGameMode(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getLevel(),
                player.getExp(),
                player.getFireTicks(),
                List.copyOf(player.getActivePotionEffects()),
                player.getAllowFlight(),
                player.isFlying());
    }

    public void restore(Player player) {
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.getInventory().setContents(contents);
        player.setGameMode(gameMode);
        player.setHealth(Math.min(health, maxHealth(player)));
        player.setFoodLevel(foodLevel);
        player.setSaturation(saturation);
        player.setLevel(level);
        player.setExp(exp);
        player.setFireTicks(fireTicks);
        player.setFallDistance(0);
        player.addPotionEffects(effects);
        player.setAllowFlight(allowFlight);
        player.setFlying(allowFlight && flying);
        player.teleport(location());
    }

    private Location location() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return Bukkit.getWorlds().getFirst().getSpawnLocation();
        }
        return new Location(w, x, y, z, yaw, pitch);
    }

    static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    public void save(ConfigurationSection sec) {
        for (int slot = 0; slot < contents.length; slot++) {
            if (contents[slot] != null && !contents[slot].isEmpty()) {
                sec.set("inventory." + slot, contents[slot]);
            }
        }
        sec.set("inventory-size", contents.length);
        sec.set("location.world", world);
        sec.set("location.x", x);
        sec.set("location.y", y);
        sec.set("location.z", z);
        sec.set("location.yaw", yaw);
        sec.set("location.pitch", pitch);
        sec.set("game-mode", gameMode.name());
        sec.set("health", health);
        sec.set("food-level", foodLevel);
        sec.set("saturation", saturation);
        sec.set("level", level);
        sec.set("exp", exp);
        sec.set("fire-ticks", fireTicks);
        sec.set("effects", effects);
        sec.set("allow-flight", allowFlight);
        sec.set("flying", flying);
    }

    public static PlayerSnapshot load(ConfigurationSection sec) {
        ItemStack[] contents = new ItemStack[sec.getInt("inventory-size", 41)];
        ConfigurationSection inventory = sec.getConfigurationSection("inventory");
        if (inventory != null) {
            for (String key : inventory.getKeys(false)) {
                int slot = Integer.parseInt(key);
                if (slot >= 0 && slot < contents.length) {
                    contents[slot] = inventory.getItemStack(key);
                }
            }
        }
        List<PotionEffect> effects = new ArrayList<>();
        for (Object o : sec.getList("effects", List.of())) {
            if (o instanceof PotionEffect effect) {
                effects.add(effect);
            }
        }
        GameMode gameMode;
        try {
            gameMode = GameMode.valueOf(sec.getString("game-mode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            gameMode = GameMode.SURVIVAL;
        }
        return new PlayerSnapshot(
                contents,
                sec.getString("location.world", ""),
                sec.getDouble("location.x"), sec.getDouble("location.y"), sec.getDouble("location.z"),
                (float) sec.getDouble("location.yaw"), (float) sec.getDouble("location.pitch"),
                gameMode,
                sec.getDouble("health", 20),
                sec.getInt("food-level", 20),
                (float) sec.getDouble("saturation", 5),
                sec.getInt("level"),
                (float) sec.getDouble("exp"),
                sec.getInt("fire-ticks"),
                effects,
                sec.getBoolean("allow-flight"),
                sec.getBoolean("flying"));
    }
}
