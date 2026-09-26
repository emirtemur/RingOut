package me.emirtemur.ringout.arena;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import me.emirtemur.ringout.RingOutPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;

/** Builds the ring a batch of blocks per tick and cleans up after games. */
public final class ArenaBuilder {

    private final RingOutPlugin plugin;
    private final Set<BuildJob> jobs = new HashSet<>();
    /** Builds not completed yet per arena, including those still waiting for chunks. */
    private final Map<Arena, Integer> running = new HashMap<>();

    public ArenaBuilder(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    /** Whether any build is in progress (arena files must not be reloaded meanwhile). */
    public boolean isBusy() {
        return !running.isEmpty();
    }

    /** Whether this arena is being built; its settings must not change meanwhile. */
    public boolean isBusy(Arena arena) {
        return running.containsKey(arena);
    }

    /**
     * Rebuilds the whole ring at the arena's current radius and clears anything left
     * of a previously larger ring. Completes on the main thread once every block is placed.
     */
    public CompletableFuture<Void> build(Arena arena) {
        World world = arena.world();
        if (world == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("world '" + arena.worldName() + "' is not loaded"));
        }
        int radius = arena.radius();
        int reach = Math.max(radius, arena.builtRadius()) + 1;
        int cx = arena.centerX();
        int cz = arena.centerZ();

        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int chunkX = (cx - reach) >> 4; chunkX <= (cx + reach) >> 4; chunkX++) {
            for (int chunkZ = (cz - reach) >> 4; chunkZ <= (cz + reach) >> 4; chunkZ++) {
                loads.add(world.getChunkAtAsync(chunkX, chunkZ));
            }
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        running.merge(arena, 1, Integer::sum);
        result.whenComplete((ignored, error) -> running.computeIfPresent(arena, (key, count) -> count > 1 ? count - 1 : null));
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                runSync(() -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    List<Chunk> chunks = loads.stream().map(CompletableFuture::join).toList();
                    new BuildJob(arena, world, radius, reach, chunks, result).start();
                }));
        return result;
    }

    /** Removes the outermost part of the ring, from oldRadius down to newRadius. */
    public void shrink(Arena arena, int oldRadius, int newRadius) {
        World world = arena.world();
        if (world == null) {
            return;
        }
        int cx = arena.centerX();
        int cy = arena.centerY();
        int cz = arena.centerZ();
        for (int dx = -oldRadius - 1; dx <= oldRadius + 1; dx++) {
            for (int dz = -oldRadius - 1; dz <= oldRadius + 1; dz++) {
                if (Arena.insideRing(dx, dz, oldRadius) && !Arena.insideRing(dx, dz, newRadius)) {
                    world.getBlockAt(cx + dx, cy, cz + dz).setType(Material.AIR, false);
                }
            }
        }
    }

    /** Turns every player-placed block back into air. */
    public void clearPlaced(Collection<BlockPos> placed) {
        for (BlockPos pos : placed) {
            World world = Bukkit.getWorld(pos.world());
            if (world != null) {
                world.getBlockAt(pos.x(), pos.y(), pos.z()).setType(Material.AIR, false);
            }
        }
    }

    /** Stops running builds and releases their chunk tickets (plugin disable). */
    public void cancelAll() {
        for (BuildJob job : List.copyOf(jobs)) {
            job.abort(new IllegalStateException("plugin disabled"));
        }
    }

    private void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private final class BuildJob extends BukkitRunnable {

        private final Arena arena;
        private final World world;
        private final int radius;
        private final int reach;
        private final List<Chunk> chunks;
        private final CompletableFuture<Void> result;
        private int dx;
        private int dz;

        BuildJob(Arena arena, World world, int radius, int reach, List<Chunk> chunks, CompletableFuture<Void> result) {
            this.arena = arena;
            this.world = world;
            this.radius = radius;
            this.reach = reach;
            this.chunks = chunks;
            this.result = result;
            this.dx = -reach;
            this.dz = -reach;
        }

        void start() {
            for (Chunk chunk : chunks) {
                chunk.addPluginChunkTicket(plugin);
            }
            runTaskTimer(plugin, 1L, 1L);
            jobs.add(this);
        }

        @Override
        public void run() {
            try {
                int budget = plugin.settings().blocksPerTick;
                int cx = arena.centerX();
                int cy = arena.centerY();
                int cz = arena.centerZ();
                while (budget-- > 0 && dx <= reach) {
                    // Outside the new ring only the old (possibly larger) ring is cleared, not the whole
                    // square, so a lobby or decoration next to the ring at the same Y is left alone.
                    Material material = Arena.insideRing(dx, dz, radius) ? arena.materialAt(dx, dz)
                            : Arena.insideRing(dx, dz, reach - 1) ? Material.AIR : null;
                    if (material != null) {
                        Block block = world.getBlockAt(cx + dx, cy, cz + dz);
                        if (block.getType() != material) {
                            block.setType(material, false);
                        }
                    }
                    if (++dz > reach) {
                        dz = -reach;
                        dx++;
                    }
                }
                if (dx > reach) {
                    finish();
                    arena.markBuilt(radius);
                    plugin.arenas().saveBuildState(arena);
                    result.complete(null);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to build the arena", e);
                abort(e);
            }
        }

        void abort(Throwable error) {
            finish();
            result.completeExceptionally(error);
        }

        private void finish() {
            if (!isCancelled()) {
                cancel();
            }
            jobs.remove(this);
            for (Chunk chunk : chunks) {
                chunk.removePluginChunkTicket(plugin);
            }
        }
    }
}
