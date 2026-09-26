package me.emirtemur.ringout.game;

import java.util.List;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.arena.Arenas;
import me.emirtemur.ringout.config.Settings;
import me.emirtemur.ringout.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
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
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class GameListener implements Listener {

    /** Explosions this close to a running ring's edge drop nothing. */
    private static final int NO_DROP_RANGE = 20;
    /** How far past its own ring edge a wind charge may still burst; half the gap between rings. */
    private static final int WIND_CHARGE_REACH = Arenas.GAP / 2;

    private final RingOutPlugin plugin;

    public GameListener(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    /** The player's game, or null when they are in the hub. */
    private Game gameOf(Player player) {
        return plugin.arenas().gameOf(player);
    }

    private Settings settings() {
        return plugin.settings();
    }

    // --- Leaving the server (joining is handled by the hub) ------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Game game = gameOf(event.getPlayer());
        if (game != null) {
            game.handleQuit(event.getPlayer());
        }
    }

    // --- Movement ----------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Game game = gameOf(player);
        if (game == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        switch (game.state()) {
            case COUNTDOWN -> {
                // Frozen in place, but free to look around.
                if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
                    event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
                }
            }
            case WAITING, BUILDING -> {
                // Anyone falling off the lobby is put back on it.
                Location lobby = game.arena().lobby();
                if (lobby.getWorld().equals(to.getWorld()) && to.getY() < lobby.getY() - settings().fallDepth) {
                    event.setTo(lobby);
                }
            }
            case ACTIVE, ENDING -> {
                if (game.isAlive(player)) {
                    if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY()
                            || from.getBlockZ() != to.getBlockZ()) {
                        // Position is re-read inside, so let the move happen first.
                        Bukkit.getScheduler().runTask(plugin, () -> game.checkPosition(player, false));
                    }
                } else if (player.getGameMode() == GameMode.SPECTATOR) {
                    game.keepSpectatorNearby(player);
                }
            }
        }
    }

    // --- Damage --------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Game game = gameOf(player);
        if (game == null) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && fromOtherGame(player, byEntity.getDamager())) {
            event.setCancelled(true);
            return;
        }
        if (game.state() != GameState.ACTIVE || !game.isAlive(player)) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                player.teleport(game.state() == GameState.ACTIVE || game.state() == GameState.ENDING
                        ? game.arena().spectatorPoint()
                        : game.arena().lobby());
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
            game.eliminate(player, "eliminated-fell");
        } else if (event.getFinalDamage() >= player.getHealth()) {
            event.setCancelled(true);
            game.eliminate(player, "eliminated-killed");
        }
    }

    /**
     * Rings can be 10 blocks apart, so a punch arrow, fishing rod or wind charge from another game
     * (or the hub) must not hurt, push or pull a player. True when the source belongs to a player
     * who is not in the victim's game.
     */
    private boolean fromOtherGame(Player victim, Entity source) {
        Player attacker = Players.attackerOf(source);
        return attacker != null && attacker != victim && gameOf(attacker) != gameOf(victim);
    }

    /**
     * A wind charge's blast pushes everyone nearby without a damage event, and Paper's knockback
     * event does not say who threw it. So a charge only bursts at its thrower's own ring and is
     * removed anywhere else (another ring, the gap between rings, the hub).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWindCharge(ProjectileHitEvent event) {
        if (event.getEntity() instanceof AbstractWindCharge charge && !mayBurstHere(charge)) {
            event.setCancelled(true);
            charge.remove();
        }
    }

    /** True for charges without a player thrower, or at most WIND_CHARGE_REACH past the thrower's own ring. */
    private boolean mayBurstHere(AbstractWindCharge charge) {
        Player thrower = Players.attackerOf(charge);
        if (thrower == null) {
            return true;
        }
        Game game = gameOf(thrower);
        Location at = charge.getLocation();
        return game != null && game.arena().isInWorld(at)
                && game.arena().horizontalDistance(at) <= game.arena().radius() + WIND_CHARGE_REACH;
    }

    /** Pushes from attacks (Paper fires this besides the damage event). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPushed(EntityPushedByEntityAttackEvent event) {
        if (event.getEntity() instanceof Player victim && fromOtherGame(victim, event.getPushedBy())) {
            event.setCancelled(true);
        }
    }

    /** A fishing hook from another game does not catch the player at all. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHookHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof FishHook hook && event.getHitEntity() instanceof Player victim
                && fromOtherGame(victim, hook)) {
            event.setCancelled(true);
            hook.remove();
        }
    }

    /** Reeling in pulls the hooked player without any damage or knockback event, so stop it here too. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onReel(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && event.getCaught() instanceof Player victim
                && fromOtherGame(victim, event.getPlayer())) {
            event.setCancelled(true);
            event.getHook().remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && gameOf(player) != null) {
            event.setCancelled(true);
        }
    }

    // --- Items and blocks ----------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Game game = gameOf(event.getPlayer());
        if (game != null && game.state() != GameState.ACTIVE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (gameOf(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Game game = gameOf(player);
        if (game == null) {
            return;
        }
        if (game.state() != GameState.ACTIVE || !game.isAlive(player)) {
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
        Game game = gameOf(event.getPlayer());
        if (game != null && game.state() == GameState.ACTIVE && game.isAlive(event.getPlayer())) {
            game.addPlaced(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Game game = gameOf(player);
        if (game == null) {
            return;
        }
        Block block = event.getBlock();
        if (game.state() != GameState.ACTIVE || !game.isAlive(player) || !game.isPlaced(block)) {
            event.setCancelled(true);
        }
    }

    /** Any game may have placed the block (an admin outside the game can break it too). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBreak(BlockBreakEvent event) {
        plugin.arenas().games().forEach(game -> game.removePlaced(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof AbstractWindCharge charge && !mayBurstHere(charge)) {
            // Blocks only: Paper pushes entities before this event, so the push itself is
            // stopped by onWindCharge cancelling the hit.
            event.setCancelled(true);
            charge.remove();
            return;
        }
        protectArenas(event.blockList());
        Location location = event.getLocation();
        boolean nearRunningRing = plugin.arenas().games().stream().anyMatch(game ->
                game.state() == GameState.ACTIVE && game.arena().isInWorld(location)
                        && game.arena().horizontalDistance(location) <= game.arena().radius() + NO_DROP_RANGE);
        if (nearRunningRing) {
            // Ring blocks should not drop as items players can pick up and re-place.
            event.setYield(0f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectArenas(event.blockList());
    }

    private void protectArenas(List<Block> blocks) {
        if (!settings().explosionsBreakArena) {
            blocks.removeIf(plugin.arenas()::isArenaBlock);
        }
    }

    /** Untracks only the blocks that really blew up, after every other plugin had its say. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackEntityExplode(EntityExplodeEvent event) {
        untrackBlown(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void trackBlockExplode(BlockExplodeEvent event) {
        untrackBlown(event.blockList());
    }

    /**
     * A block is only untracked once it is really gone. A wind charge burst lists nearby blocks
     * without breaking them; untracking those would leave them in the arena after the match.
     */
    private void untrackBlown(List<Block> blocks) {
        if (!plugin.isEnabled()) {
            return;
        }
        List<Block> listed = List.copyOf(blocks);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Block block : listed) {
                if (block.getType().isAir()) {
                    plugin.arenas().games().forEach(game -> game.removePlaced(block));
                }
            }
        });
    }
}
