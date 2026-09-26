package me.emirtemur.ringout.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

/** One ring: geometry, look and lobby, persisted in arenas/<name>.yml. */
public final class Arena {

    private static final double TWO_PI = Math.PI * 2;

    private final String name;
    private String worldName;
    private int centerX;
    private int centerY;
    private int centerZ;
    private int radius;
    private int slices;
    private final List<Material> colors;
    private final Material edge;
    private final Material core;
    private final int coreRadius;

    private boolean hasLobby;
    private String lobbyWorld;
    private double lobbyX;
    private double lobbyY;
    private double lobbyZ;
    private float lobbyYaw;
    private float lobbyPitch;

    private boolean built;
    private int builtRadius;
    /** Build state changed in memory but not written to the arena file yet. */
    private boolean buildStateDirty;
    /** Admin settings (center, radius, slices, lobby) changed in memory but not written yet. */
    private boolean settingsDirty;

    private Arena(String name, List<Material> colors, Material edge, Material core, int coreRadius) {
        this.name = name;
        this.colors = colors;
        this.edge = edge;
        this.core = core;
        this.coreRadius = coreRadius;
    }

    public static Arena load(String name, ConfigurationSection sec, Logger log) {
        String where = name + ".yml";
        List<Material> colors = new ArrayList<>();
        for (String color : sec.getStringList("colors")) {
            Material material = parseBlock(color, log, where + " colors");
            if (material != null) {
                colors.add(material);
            }
        }
        if (colors.isEmpty()) {
            colors.add(Material.WHITE_CONCRETE);
        }
        Material edge = parseBlock(sec.getString("edge", "WHITE_CONCRETE"), log, where + " edge");
        Material core = parseBlock(sec.getString("core", "OBSIDIAN"), log, where + " core");

        Arena arena = new Arena(name, colors,
                edge != null ? edge : Material.WHITE_CONCRETE,
                core != null ? core : Material.OBSIDIAN,
                Math.max(0, sec.getInt("core-radius", 2)));
        arena.worldName = sec.getString("world", "ringout_world");
        arena.centerX = sec.getInt("center.x", 0);
        arena.centerY = sec.getInt("center.y", 150);
        arena.centerZ = sec.getInt("center.z", 0);
        arena.radius = clamp(sec.getInt("radius", 20), 3, 100);
        arena.slices = clamp(sec.getInt("slices", 8), 2, 16);
        arena.hasLobby = sec.getBoolean("lobby.set", false);
        arena.lobbyWorld = sec.getString("lobby.world", arena.worldName);
        arena.lobbyX = sec.getDouble("lobby.x");
        arena.lobbyY = sec.getDouble("lobby.y");
        arena.lobbyZ = sec.getDouble("lobby.z");
        arena.lobbyYaw = (float) sec.getDouble("lobby.yaw");
        arena.lobbyPitch = (float) sec.getDouble("lobby.pitch");
        arena.built = sec.getBoolean("built", false);
        arena.builtRadius = sec.getInt("built-radius", 0);
        return arena;
    }

    public void save(ConfigurationSection sec) {
        sec.set("world", worldName);
        sec.set("center.x", centerX);
        sec.set("center.y", centerY);
        sec.set("center.z", centerZ);
        sec.set("radius", radius);
        sec.set("slices", slices);
        sec.set("lobby.set", hasLobby);
        if (hasLobby) {
            sec.set("lobby.world", lobbyWorld);
            sec.set("lobby.x", lobbyX);
            sec.set("lobby.y", lobbyY);
            sec.set("lobby.z", lobbyZ);
            sec.set("lobby.yaw", lobbyYaw);
            sec.set("lobby.pitch", lobbyPitch);
        }
        sec.set("built", built);
        sec.set("built-radius", builtRadius);
    }

    /**
     * A new, not yet built arena. Radius, slices and look come from the arena-defaults section
     * of config.yml; afterwards each arena file can be tuned on its own.
     */
    public static Arena create(String name, String worldName, int x, int y, int z,
                               ConfigurationSection defaults, Logger log) {
        Arena arena = load(name, defaults, log);
        arena.setCenter(worldName, x, y, z);
        return arena;
    }

    /** Writes the look (colors, edge, core); only done when the file is created. */
    public void saveAppearance(ConfigurationSection sec) {
        sec.set("colors", colors.stream().map(Material::name).toList());
        sec.set("edge", edge.name());
        sec.set("core", core.name());
        sec.set("core-radius", coreRadius);
    }

    /**
     * Whether a ring at the given spot would come closer than the gap to this one (so knocked
     * players and explosions stay apart). Uses the larger of this ring's configured and built radius.
     */
    public boolean touches(String world, int x, int z, int otherRadius, int gap) {
        if (!worldName.equals(world)) {
            return false;
        }
        double dx = centerX - x;
        double dz = centerZ - z;
        int reach = Math.max(radius, builtRadius) + otherRadius + gap;
        return dx * dx + dz * dz < (double) reach * reach;
    }

    private static Material parseBlock(String name, Logger log, String path) {
        Material material = name == null ? null : Material.matchMaterial(name);
        if (material == null || !material.isBlock()) {
            log.warning("Invalid block '" + name + "' in " + path + ", skipping it.");
            return null;
        }
        return material;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // --- Geometry ---------------------------------------------------------

    /** Whether the block column at this offset from the center is part of a ring of the given radius. */
    public static boolean insideRing(int dx, int dz, int radius) {
        double limit = radius + 0.5;
        return dx * dx + dz * dz <= limit * limit;
    }

    /** Block material for the ring column at this offset. Only valid when insideRing. */
    public Material materialAt(int dx, int dz) {
        int d2 = dx * dx + dz * dz;
        double coreLimit = coreRadius + 0.5;
        if (coreRadius > 0 && d2 <= coreLimit * coreLimit) {
            return core;
        }
        double edgeLimit = radius - 0.5;
        if (d2 > edgeLimit * edgeLimit) {
            return edge;
        }
        double angle = Math.atan2(dz, dx);
        if (angle < 0) {
            angle += TWO_PI;
        }
        int slice = Math.min(slices - 1, (int) (angle / (TWO_PI / slices)));
        return colors.get(slice % colors.size());
    }

    /** Whether the block is part of the configured ring (used to protect it). */
    public boolean isArenaBlock(Block block) {
        return block.getY() == centerY
                && block.getWorld().getName().equals(worldName)
                && insideRing(block.getX() - centerX, block.getZ() - centerZ, radius);
    }

    /** Horizontal distance from the ring's center point. */
    public double horizontalDistance(Location location) {
        double dx = location.getX() - (centerX + 0.5);
        double dz = location.getZ() - (centerZ + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean isInWorld(Location location) {
        return location.getWorld() != null && location.getWorld().getName().equals(worldName);
    }

    /** Spawn point on a slice, facing the center. Players are spread over slices evenly. */
    public Location spawnPoint(int index, int count) {
        double angle;
        if (count <= slices) {
            int slice = (int) ((long) index * slices / count);
            angle = (slice + 0.5) * TWO_PI / slices;
        } else {
            angle = (index + 0.5) * TWO_PI / count;
        }
        double distance = Math.max(coreRadius + 2, radius * 0.6);
        double x = Math.floor(centerX + 0.5 + Math.cos(angle) * distance) + 0.5;
        double z = Math.floor(centerZ + 0.5 + Math.sin(angle) * distance) + 0.5;
        Location location = new Location(world(), x, centerY + 1, z);
        location.setDirection(new Vector(centerX + 0.5 - x, 0, centerZ + 0.5 - z));
        return location;
    }

    public Location centerPoint() {
        return new Location(world(), centerX + 0.5, centerY + 1, centerZ + 0.5);
    }

    public Location spectatorPoint() {
        return new Location(world(), centerX + 0.5, centerY + 10, centerZ + 0.5, 0, 60);
    }

    public Location lobby() {
        if (hasLobby) {
            World world = Bukkit.getWorld(lobbyWorld);
            if (world != null) {
                return new Location(world, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyPitch);
            }
        }
        return centerPoint();
    }

    // --- State ------------------------------------------------------------

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public boolean isReady() {
        return built && world() != null;
    }

    /** Records a finished build; the build state stays dirty until it is saved to the arena file. */
    public void markBuilt(int builtRadius) {
        if (!built || this.builtRadius != builtRadius) {
            buildStateDirty = true;
        }
        this.built = true;
        this.builtRadius = builtRadius;
    }

    public boolean isBuildStateDirty() {
        return buildStateDirty;
    }

    public boolean isSettingsDirty() {
        return settingsDirty;
    }

    /** Everything in memory now matches the arena file. */
    public void markSaved() {
        buildStateDirty = false;
        settingsDirty = false;
    }

    /** Writes only the build state, leaving the other arena keys on disk untouched. */
    public void saveBuildState(ConfigurationSection sec) {
        sec.set("built", built);
        sec.set("built-radius", builtRadius);
    }

    public void setCenter(String worldName, int x, int y, int z) {
        this.worldName = worldName;
        this.centerX = x;
        this.centerY = y;
        this.centerZ = z;
        this.built = false;
        this.builtRadius = 0;
        settingsDirty = true;
    }

    public void setLobby(Location location) {
        hasLobby = true;
        lobbyWorld = location.getWorld().getName();
        lobbyX = location.getX();
        lobbyY = location.getY();
        lobbyZ = location.getZ();
        lobbyYaw = location.getYaw();
        lobbyPitch = location.getPitch();
        settingsDirty = true;
    }

    public void setRadius(int radius) {
        this.radius = radius;
        settingsDirty = true;
    }

    public void setSlices(int slices) {
        this.slices = slices;
        settingsDirty = true;
    }

    public String name() {
        return name;
    }

    public boolean isBuilt() {
        return built;
    }

    public String worldName() {
        return worldName;
    }

    public int centerX() {
        return centerX;
    }

    public int centerY() {
        return centerY;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public int slices() {
        return slices;
    }

    public int builtRadius() {
        return builtRadius;
    }
}
