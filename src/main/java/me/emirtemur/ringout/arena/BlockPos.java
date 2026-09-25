package me.emirtemur.ringout.arena;

import org.bukkit.block.Block;

/** World-qualified block coordinates, safe to use as a set key. */
public record BlockPos(String world, int x, int y, int z) {

    public static BlockPos of(Block block) {
        return new BlockPos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }
}
