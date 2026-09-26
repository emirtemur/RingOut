package me.emirtemur.ringout.hub;

import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.util.Players;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.InventoryHolder;

/** Sends joining players to the hub and keeps players who are not in a game safe there. */
public final class HubListener implements Listener {

    private final RingOutPlugin plugin;

    public HubListener(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    /** In the hub: online but not part of a game. */
    private boolean inHub(Player player) {
        return plugin.arenas().gameOf(player) == null;
    }

    /** In the hub and not allowed to change it. */
    private boolean guarded(Player player) {
        return inHub(player) && !player.hasPermission(Hub.BYPASS_PERMISSION);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(Hub.BYPASS_PERMISSION)) {
            plugin.sendToHub(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !inHub(player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            player.teleport(plugin.hub().location());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        // Also arrows, snowballs and TNT from the hub, not only direct hits.
        Player attacker = Players.attackerOf(event.getDamager());
        if (attacker != null && guarded(attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && inHub(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        cancelIfGuarded(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        cancelIfGuarded(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        cancelIfGuarded(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        cancelIfGuarded(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        cancelIfGuarded(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfGuarded(player, event);
        }
    }

    /** No trampling farmland or opening chests, barrels and the like; doors and buttons still work. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (!guarded(event.getPlayer())) {
            return;
        }
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true);
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null
                && block.getState(false) instanceof InventoryHolder) {
            // Deny only the block, so an item in hand (like the arena menu later) keeps working.
            event.setUseInteractedBlock(Event.Result.DENY);
        }
    }

    private void cancelIfGuarded(Player player, Cancellable event) {
        if (guarded(player)) {
            event.setCancelled(true);
        }
    }
}
