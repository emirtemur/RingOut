package me.emirtemur.ringout.util;

import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;

/** Small player helpers shared by the hub and the games. */
public final class Players {

    private Players() {
    }

    /** Empties the inventory and puts the player back to full health, food and no effects. */
    public static void reset(Player player, GameMode gameMode) {
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGameMode(gameMode);
        player.setAllowFlight(false);
        player.setHealth(maxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    /**
     * The player behind a hit: the attacker itself, the shooter of a projectile (arrows,
     * snowballs, eggs, fishing hooks, wind charges) or whoever lit the TNT. Null for anything else.
     */
    public static Player attackerOf(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player source) {
            return source;
        }
        return null;
    }

    public static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    /**
     * Removes the player's ender pearls still in flight. Since 1.21.2 a pearl keeps its owner
     * even after they leave (it is saved with them and follows across worlds), so a pearl thrown
     * just before leaving a game would pull them back into the arena.
     */
    public static void discardPearls(Player player) {
        List.copyOf(player.getEnderPearls()).forEach(Entity::remove);
    }
}
