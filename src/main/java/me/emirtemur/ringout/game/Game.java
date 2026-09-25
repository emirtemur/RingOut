package me.emirtemur.ringout.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.arena.Arena;
import me.emirtemur.ringout.arena.BlockPos;
import me.emirtemur.ringout.config.Settings;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/** The single RingOut match: lobby, countdown, fight, elimination and cleanup. */
public final class Game {

    /** Period of the fight loop in ticks. */
    private static final int LOOP_PERIOD = 5;

    private final RingOutPlugin plugin;

    private final Set<UUID> players = new LinkedHashSet<>();
    private final Set<UUID> alive = new LinkedHashSet<>();
    private final Map<UUID, PlayerSnapshot> snapshots = new HashMap<>();
    private final Map<UUID, Integer> outsideTicks = new HashMap<>();
    private final Set<BlockPos> placedBlocks = new HashSet<>();
    private final BossBar bossBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);

    private GameState state = GameState.WAITING;
    /** Bumped whenever a match is torn down, so stale async callbacks can tell they are outdated. */
    private int generation;
    private boolean forced;

    private BukkitTask lobbyTask;
    private BukkitTask countdownTask;
    private BukkitTask loopTask;
    private BukkitTask endTask;
    private BukkitTask fireworkTask;

    private int startedWith;
    private int elapsedSeconds;
    private int nextItemIn;
    private int loopTicks;
    private int currentRadius;
    private boolean suddenDeath;
    private int shrinkTimer;

    public Game(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    // --- Queries -----------------------------------------------------------

    public GameState state() {
        return state;
    }

    public boolean isPlaying(Player player) {
        return players.contains(player.getUniqueId());
    }

    public boolean isAlive(Player player) {
        return alive.contains(player.getUniqueId());
    }

    public int currentRadius() {
        return currentRadius;
    }

    public boolean isPlaced(Block block) {
        return placedBlocks.contains(BlockPos.of(block));
    }

    public void addPlaced(Block block) {
        placedBlocks.add(BlockPos.of(block));
    }

    public void removePlaced(Block block) {
        placedBlocks.remove(BlockPos.of(block));
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Arena arena() {
        return plugin.arena();
    }

    // --- Joining and leaving -------------------------------------------------

    public void join(Player player) {
        Settings s = settings();
        if (isPlaying(player)) {
            player.sendMessage(s.message("already-in-game"));
            return;
        }
        if (state == GameState.BUILDING && players.isEmpty()) {
            player.sendMessage(s.message("arena-resetting"));
            return;
        }
        if (state != GameState.WAITING) {
            player.sendMessage(s.message("game-running"));
            return;
        }
        if (!arena().isReady()) {
            player.sendMessage(s.message("arena-not-ready"));
            return;
        }
        if (players.size() >= s.maxPlayers) {
            player.sendMessage(s.message("game-full"));
            return;
        }

        UUID id = player.getUniqueId();
        if (plugin.snapshots().has(id)) {
            // A leftover from an interrupted game must be restored first, never overwritten.
            plugin.restoreLeftover(player);
            if (plugin.snapshots().has(id)) {
                player.sendMessage(s.message("snapshot-pending"));
                return;
            }
        }
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        if (!plugin.snapshots().save(id, snapshot)) {
            player.sendMessage(s.message("snapshot-save-failed"));
            return;
        }
        snapshots.put(id, snapshot);
        players.add(id);

        prepare(player, GameMode.ADVENTURE);
        player.teleport(arena().lobby());
        broadcast("joined", Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("count", String.valueOf(players.size())),
                Placeholder.unparsed("max", String.valueOf(s.maxPlayers)));

        if (players.size() >= s.maxPlayers) {
            start(false);
        } else if (s.autoStart && players.size() >= s.minPlayers) {
            startLobbyCountdown();
        } else {
            player.sendMessage(s.message("waiting",
                    Placeholder.unparsed("count", String.valueOf(players.size())),
                    Placeholder.unparsed("min", String.valueOf(s.minPlayers))));
        }
    }

    public void leave(Player player) {
        if (!isPlaying(player)) {
            player.sendMessage(settings().message("not-in-game"));
            return;
        }
        remove(player, false);
    }

    public void handleQuit(Player player) {
        if (isPlaying(player)) {
            // The snapshot stays on disk and is restored when the player comes back.
            remove(player, true);
        }
    }

    private void remove(Player player, boolean quit) {
        UUID id = player.getUniqueId();
        boolean wasAlive = state == GameState.ACTIVE && alive.contains(id);
        players.remove(id);
        alive.remove(id);
        outsideTicks.remove(id);
        player.hideBossBar(bossBar);
        PlayerSnapshot snapshot = snapshots.remove(id);
        if (!quit) {
            restore(player, snapshot);
        }

        Settings s = settings();
        if (wasAlive) {
            broadcast("eliminated-left", Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("alive", String.valueOf(alive.size())));
            updateBossBar();
            checkWin();
            return;
        }
        if (state == GameState.ACTIVE || state == GameState.ENDING) {
            if (players.isEmpty()) {
                finish();
            }
            return;
        }
        broadcast("left", Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("count", String.valueOf(players.size())),
                Placeholder.unparsed("max", String.valueOf(s.maxPlayers)));

        int needed = forced ? 1 : s.minPlayers;
        if (state == GameState.WAITING && lobbyTask != null && players.size() < s.minPlayers) {
            cancel(lobbyTask);
            lobbyTask = null;
            broadcast("lobby-cancelled");
        } else if (state == GameState.COUNTDOWN && players.size() < needed) {
            cancel(countdownTask);
            countdownTask = null;
            state = GameState.WAITING;
            broadcast("lobby-cancelled");
            for (Player p : onlinePlayers()) {
                p.teleport(arena().lobby());
            }
        }
        // BUILDING re-checks the player count once the ring is ready.
    }

    // --- Starting ---------------------------------------------------------

    private void startLobbyCountdown() {
        if (lobbyTask != null || state != GameState.WAITING) {
            return;
        }
        int wait = settings().lobbyWait;
        if (wait <= 0) {
            start(false);
            return;
        }
        int[] remaining = {wait};
        lobbyTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (remaining[0] <= 0) {
                cancel(lobbyTask);
                lobbyTask = null;
                start(false);
                return;
            }
            int sec = remaining[0];
            if (sec == wait || sec == 10 || sec <= 5) {
                broadcast("lobby-countdown", Placeholder.unparsed("seconds", String.valueOf(sec)));
                playSound(Sound.BLOCK_NOTE_BLOCK_HAT, 1f);
            }
            remaining[0]--;
        }, 0L, 20L);
    }

    /** Starts the match. Forced starts only need one player (handy for testing). */
    public boolean start(boolean force) {
        Settings s = settings();
        if (state != GameState.WAITING) {
            return false;
        }
        if (players.size() < (force ? 1 : s.minPlayers)) {
            return false;
        }
        cancel(lobbyTask);
        lobbyTask = null;
        forced = force;
        state = GameState.BUILDING;
        broadcast("preparing");

        plugin.arenaBuilder().clearPlaced(placedBlocks);
        placedBlocks.clear();
        clearLooseEntities();

        int gen = generation;
        plugin.arenaBuilder().build(arena()).whenComplete((ignored, error) -> {
            if (gen != generation || state != GameState.BUILDING) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not build the arena", error);
                broadcast("arena-build-failed", Placeholder.unparsed("error", String.valueOf(error.getMessage())));
                state = GameState.WAITING;
                return;
            }
            beginCountdown();
        });
        return true;
    }

    private void beginCountdown() {
        Settings s = settings();
        List<Player> online = onlinePlayers();
        if (online.size() < (forced ? 1 : s.minPlayers)) {
            state = GameState.WAITING;
            broadcast("lobby-cancelled");
            return;
        }
        state = GameState.COUNTDOWN;
        currentRadius = arena().radius();
        for (int i = 0; i < online.size(); i++) {
            Player p = online.get(i);
            prepare(p, GameMode.ADVENTURE);
            p.teleport(arena().spawnPoint(i, online.size()));
        }

        int[] remaining = {s.countdown};
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (remaining[0] <= 0) {
                cancel(countdownTask);
                countdownTask = null;
                beginFight();
                return;
            }
            Component title = s.text("countdown-title", Placeholder.unparsed("seconds", String.valueOf(remaining[0])));
            showTitle(title, s.text("countdown-subtitle"));
            playSound(Sound.BLOCK_NOTE_BLOCK_PLING, 1f);
            remaining[0]--;
        }, 0L, 20L);
    }

    private void beginFight() {
        Settings s = settings();
        state = GameState.ACTIVE;
        alive.clear();
        alive.addAll(players);
        startedWith = alive.size();
        elapsedSeconds = 0;
        loopTicks = 0;
        suddenDeath = false;
        shrinkTimer = 0;
        outsideTicks.clear();

        showTitle(s.text("start-title"), s.text("start-subtitle"));
        playSound(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f);
        bossBar.color(BossBar.Color.YELLOW);
        for (Player p : onlinePlayers()) {
            p.setGameMode(GameMode.SURVIVAL);
            p.showBossBar(bossBar);
        }
        giveItems();
        nextItemIn = s.itemInterval;
        updateBossBar();
        loopTask = Bukkit.getScheduler().runTaskTimer(plugin, this::loop, LOOP_PERIOD, LOOP_PERIOD);
    }

    // --- Fight loop ---------------------------------------------------------

    private void loop() {
        if (state != GameState.ACTIVE) {
            return;
        }
        for (UUID id : List.copyOf(alive)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                checkPosition(p, true);
                if (state != GameState.ACTIVE) {
                    return;
                }
            }
        }
        loopTicks += LOOP_PERIOD;
        if (loopTicks >= 20) {
            loopTicks -= 20;
            secondPassed();
        }
    }

    private void secondPassed() {
        Settings s = settings();
        elapsedSeconds++;
        if (--nextItemIn <= 0) {
            giveItems();
            nextItemIn = s.itemInterval;
        }
        if (s.suddenDeathEnabled) {
            if (!suddenDeath && elapsedSeconds >= s.suddenDeathStartAfter) {
                suddenDeath = true;
                shrinkTimer = 0;
                bossBar.color(BossBar.Color.RED);
                broadcast("sudden-death");
                playSound(Sound.ENTITY_WITHER_SPAWN, 1f);
            }
            if (suddenDeath && ++shrinkTimer >= s.shrinkInterval && currentRadius > s.minRadius) {
                shrinkTimer = 0;
                int newRadius = currentRadius - 1;
                plugin.arenaBuilder().shrink(arena(), currentRadius, newRadius);
                currentRadius = newRadius;
                playSound(Sound.BLOCK_GLASS_BREAK, 0.8f);
            }
        }
        updateBossBar();
    }

    /**
     * Eliminates the player if they left the ring. With {@code timed} the outside-grace
     * counter advances, so a player standing on a bridge outside the ring is caught too.
     */
    public void checkPosition(Player player, boolean timed) {
        if (state != GameState.ACTIVE || !isAlive(player)) {
            return;
        }
        Settings s = settings();
        Arena a = arena();
        Location loc = player.getLocation();
        if (!a.isInWorld(loc) || loc.getY() < a.centerY() - s.fallDepth) {
            eliminate(player, "eliminated-fell");
            return;
        }
        UUID id = player.getUniqueId();
        if (a.horizontalDistance(loc) > currentRadius + 0.5 + s.margin) {
            if (loc.getY() < a.centerY()) {
                eliminate(player, "eliminated-fell");
            } else if (timed && standing(player)
                    && outsideTicks.merge(id, LOOP_PERIOD, Integer::sum) > s.outsideGraceTicks) {
                // Only time spent standing outside counts, so a wind charge arc past the edge is not a ring out.
                eliminate(player, "eliminated-outside");
            }
        } else if (timed) {
            outsideTicks.remove(id);
        }
    }

    /** Entity#isOnGround, called through Entity because the Player override is deprecated (client-reported). */
    private static boolean standing(Entity entity) {
        return entity.isOnGround();
    }

    private void giveItems() {
        Settings s = settings();
        for (Player p : alivePlayers()) {
            List<ItemStack> items = plugin.itemPool().roll();
            if (items.isEmpty()) {
                continue;
            }
            p.getInventory().addItem(items.toArray(ItemStack[]::new)).values()
                    .forEach(leftover -> p.getWorld().dropItem(p.getLocation(), leftover));
            p.sendMessage(s.message("item-received", Placeholder.component("item", items.getFirst().displayName())));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
        }
    }

    private void updateBossBar() {
        Settings s = settings();
        String aliveCount = String.valueOf(alive.size());
        if (suddenDeath) {
            bossBar.name(s.text("bossbar-sudden-death", Placeholder.unparsed("alive", aliveCount),
                    Placeholder.unparsed("radius", String.valueOf(currentRadius))));
            bossBar.progress(clamp01((float) currentRadius / Math.max(1, arena().radius())));
        } else {
            bossBar.name(s.text("bossbar", Placeholder.unparsed("alive", aliveCount),
                    Placeholder.unparsed("seconds", String.valueOf(Math.max(0, nextItemIn)))));
            bossBar.progress(clamp01((float) nextItemIn / s.itemInterval));
        }
    }

    // --- Elimination and the end ---------------------------------------------

    public void eliminate(Player player, String messageKey) {
        UUID id = player.getUniqueId();
        if (state != GameState.ACTIVE || !alive.remove(id)) {
            return;
        }
        Settings s = settings();
        outsideTicks.remove(id);
        player.getInventory().clear();
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setVelocity(new Vector());
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(arena().spectatorPoint());
        player.showTitle(Title.title(s.text("eliminated-title"), s.text("eliminated-subtitle")));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);

        broadcast(messageKey, Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("alive", String.valueOf(alive.size())));
        updateBossBar();
        checkWin();
    }

    private void checkWin() {
        if (state != GameState.ACTIVE) {
            return;
        }
        if (alive.isEmpty() || (startedWith > 1 && alive.size() <= 1)) {
            Player winner = alive.isEmpty() ? null : Bukkit.getPlayer(alive.iterator().next());
            end(winner);
        }
    }

    private void end(Player winner) {
        Settings s = settings();
        state = GameState.ENDING;
        cancel(loopTask);
        loopTask = null;
        for (Player p : onlinePlayers()) {
            p.hideBossBar(bossBar);
        }

        if (winner != null) {
            TagResolver name = Placeholder.unparsed("player", winner.getName());
            broadcast("win-broadcast", name);
            showTitle(s.text("win-title", name), s.text("win-subtitle"));
            playSound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
            for (String command : s.winCommands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", winner.getName()));
            }
            UUID winnerId = winner.getUniqueId();
            fireworkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                Player w = Bukkit.getPlayer(winnerId);
                if (w != null && w.getGameMode() != GameMode.SPECTATOR) {
                    launchFirework(w.getLocation());
                }
            }, 0L, 15L);
        } else {
            broadcast("no-winner");
        }
        endTask = Bukkit.getScheduler().runTaskLater(plugin, this::finish, s.endingSeconds * 20L);
    }

    /** Sends everyone back and resets the arena for the next match. */
    private void finish() {
        cancelTasks();
        for (UUID id : List.copyOf(players)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bossBar);
                restore(p, snapshots.get(id));
            }
        }
        resetState();
        plugin.arenaBuilder().clearPlaced(placedBlocks);
        placedBlocks.clear();
        clearLooseEntities();

        state = GameState.BUILDING;
        int gen = generation;
        plugin.arenaBuilder().build(arena()).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not reset the arena", error);
            }
            if (gen == generation && state == GameState.BUILDING) {
                state = GameState.WAITING;
            }
        });
    }

    /** Admin stop. Returns false when there was nothing to stop. */
    public boolean stop() {
        if (players.isEmpty() && state == GameState.WAITING) {
            return false;
        }
        broadcast("game-stopped");
        finish();
        return true;
    }

    /** Plugin disable: put everyone back right now, no scheduling possible anymore. */
    public void shutdown() {
        cancelTasks();
        for (UUID id : List.copyOf(players)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bossBar);
                restore(p, snapshots.get(id));
            }
        }
        resetState();
        plugin.arenaBuilder().clearPlaced(placedBlocks);
        placedBlocks.clear();
        state = GameState.WAITING;
    }

    private void resetState() {
        generation++;
        players.clear();
        alive.clear();
        snapshots.clear();
        outsideTicks.clear();
        forced = false;
        suddenDeath = false;
    }

    private void cancelTasks() {
        cancel(lobbyTask);
        cancel(countdownTask);
        cancel(loopTask);
        cancel(endTask);
        cancel(fireworkTask);
        lobbyTask = countdownTask = loopTask = endTask = fireworkTask = null;
    }

    // --- Helpers --------------------------------------------------------------

    /** Clears a player for play without touching their saved state. */
    private void prepare(Player player, GameMode gameMode) {
        player.getInventory().clear();
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGameMode(gameMode);
        player.setAllowFlight(false);
        player.setHealth(PlayerSnapshot.maxHealth(player));
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setFireTicks(0);
        player.setFallDistance(0);
    }

    /** Restores from memory, falling back to disk, then removes the disk copy. */
    private void restore(Player player, PlayerSnapshot snapshot) {
        UUID id = player.getUniqueId();
        PlayerSnapshot snap = snapshot != null ? snapshot : plugin.snapshots().load(id).orElse(null);
        if (snap != null) {
            snap.restore(player);
        }
        plugin.snapshots().delete(id);
    }

    /** Pulls a wandering spectator back to the ring. */
    public void keepSpectatorNearby(Player player) {
        Arena a = arena();
        Location loc = player.getLocation();
        int max = settings().spectatorMaxDistance > 0 ? settings().spectatorMaxDistance : a.radius() + 30;
        if (!a.isInWorld(loc) || a.horizontalDistance(loc) > max || Math.abs(loc.getY() - a.centerY()) > max) {
            player.teleport(a.spectatorPoint());
        }
    }

    private void clearLooseEntities() {
        World world = arena().world();
        if (world == null) {
            return;
        }
        double range = arena().radius() + 40;
        for (Entity entity : world.getNearbyEntities(arena().centerPoint(), range, range, range)) {
            if (entity instanceof Item || entity instanceof Projectile || entity instanceof TNTPrimed) {
                entity.remove();
            }
        }
    }

    private void launchFirework(Location location) {
        Color[] colors = {Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.FUCHSIA};
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location.getWorld().spawn(location.clone().add(0, 1, 0), Firework.class, firework -> {
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(colors[random.nextInt(colors.length)], colors[random.nextInt(colors.length)])
                    .withFade(Color.WHITE)
                    .flicker(true)
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        });
    }

    private List<Player> onlinePlayers() {
        List<Player> list = new ArrayList<>();
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private List<Player> alivePlayers() {
        return alive.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
    }

    private void broadcast(String key, TagResolver... resolvers) {
        Component message = settings().message(key, resolvers);
        onlinePlayers().forEach(p -> p.sendMessage(message));
    }

    private void showTitle(Component title, Component subtitle) {
        Title t = Title.title(title, subtitle);
        onlinePlayers().forEach(p -> p.showTitle(t));
    }

    private void playSound(Sound sound, float pitch) {
        onlinePlayers().forEach(p -> p.playSound(p.getLocation(), sound, 1f, pitch));
    }

    private static void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
