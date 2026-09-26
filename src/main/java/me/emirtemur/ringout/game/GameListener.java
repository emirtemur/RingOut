package me.emirtemur.ringout.game;

import java.util.List;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class GameListener implements Listener {

    private final RingOutPlugin plugin;

    public GameListener(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    private Game game() {
        return plugin.game();
    }

    private Settings settings() {
        return plugin.settings();
    }

    // --- Leaving the server (joining is handled by the hub) ------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        game().handleQuit(event.getPlayer());
    }

    // --- Movement ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!game().isPlaying(player)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        switch (game().state()) {
            case COUNTDOWN -> {
                // Frozen in place, but free to look around.
                if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                    event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
                }
            }
            case WAITING, BUILDING -> {
                // Anyone falling off the lobby is put back on it.
                Location lobby = plugin.arena().lobby();
                if (lobby.getWorld().equals(to.getWorld()) && to.getY() < lobby.getY() - settings().fallDepth) {
                    event.setTo(lobby);
                }
            }
            case ACTIVE, ENDING -> {
                if (game().isAlive(player)) {
                    if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                            || from.getBlockZ() != to.getBlockZ()) {
                        // Position is re-read inside, so let the move happen first.
                        Bukkit.getScheduler().runTask(plugin, () -> game().checkPosition(player, false));
                    }
                } else if (player.getGameMode() == GameMode.SPECTATOR) {
                    game().keepSpectatorNearby(player);
                }
            }
        }
    }

    // --- Damage --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !game().isPlaying(player)) {
            return;
        }
        if (game().state() != GameState.ACTIVE || !game().isAlive(player)) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                player.teleport(game().state() == GameState.ACTIVE || game().state() == GameState.ENDING
                        ? plugin.arena().spectatorPoint()
                        : plugin.arena().lobby());
            }
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
            return;
        }
        if (!settings().pvpDamage && event instanceof EntityDamageByEntityEvent) {
            // Keep the knockback, drop the damage.
            event.setDamage(0);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
            game().eliminate(player, "eliminated-fell");
        } else if (event.getFinalDamage() >= player.getHealth()) {
            event.setCancelled(true);
            game().eliminate(player, "eliminated-killed");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && game().isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    // --- Items and blocks ----------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (game().isPlaying(event.getPlayer()) && game().state() != GameState.ACTIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (game().isPlaying(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!game().isPlaying(player)) {
            return;
        }
        if (game().state() != GameState.ACTIVE || !game().isAlive(player)) {
            event.setCancelled(true);
            return;
        }
        Block block = event.getBlockPlaced();
        if (block.getType() == Material.TNT && settings().autoPrimeTnt) {
            event.setCancelled(true);
            ItemStack hand = player.getInventory().getItem(event.getHand());
            if (player.getGameMode() != GameMode.CREATIVE && hand != null) {
                hand.setAmount(hand.getAmount() - 1);
            }
            block.getWorld().spawn(block.getLocation().add(0.5, 0, 0.5), TNTPrimed.class, tnt -> {
                tnt.setFuseTicks(settings().tntFuseTicks);
                tnt.setSource(player);
            });
        }
    }

    /** Tracks at MONITOR so a placement another plugin cancels later is not recorded. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackPlace(BlockPlaceEvent event) {
        if (game().state() == GameState.ACTIVE && game().isAlive(event.getPlayer())) {
            game().addPlaced(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!game().isPlaying(player)) {
            return;
        }
        Block block = event.getBlock();
        if (game().state() != GameState.ACTIVE || !game().isAlive(player) || !game().isPlaced(block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBreak(BlockBreakEvent event) {
        game().removePlaced(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectArena(event.blockList());
        if (game().state() == GameState.ACTIVE && plugin.arena().isInWorld(event.getLocation())) {
            // Ring blocks should not drop as items players can pick up and re-place.
            event.setYield(0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectArena(event.blockList());
    }

    private void protectArena(List<Block> blocks) {
        if (!settings().explosionsBreakArena) {
            blocks.removeIf(plugin.arena()::isArenaBlock);
        }
    }

    /** Untracks only the blocks that really blew up, after every other plugin had its say. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(game()::removePlaced);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(game()::removePlaced);
    }
}
