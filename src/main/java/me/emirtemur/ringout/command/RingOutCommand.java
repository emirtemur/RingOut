package me.emirtemur.ringout.command;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.arena.Arena;
import me.emirtemur.ringout.config.Settings;
import me.emirtemur.ringout.game.GameState;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class RingOutCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBCOMMANDS = List.of("join", "leave");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "start", "stop", "setcenter", "setlobby", "sethub", "radius", "slices", "build", "createworld", "reload");

    private final RingOutPlugin plugin;

    public RingOutCommand(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings settings() {
        return plugin.settings();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(settings().message("usage"));
            return true;
        }
        String sub = args[0].toLowerCase();
        if (ADMIN_SUBCOMMANDS.contains(sub) && !sender.hasPermission("ringout.admin")) {
            sender.sendMessage(settings().message("no-permission"));
            return true;
        }
        if (PLAYER_SUBCOMMANDS.contains(sub) && !sender.hasPermission("ringout.play")) {
            sender.sendMessage(settings().message("no-permission"));
            return true;
        }
        switch (sub) {
            case "join" -> withPlayer(sender, player -> plugin.game().join(player));
            case "leave" -> withPlayer(sender, player -> plugin.game().leave(player));
            case "start" -> start(sender);
            case "stop" -> sender.sendMessage(settings().message(plugin.game().stop() ? "stopped" : "no-game"));
            case "setcenter" -> setCenter(sender, args);
            case "setlobby" -> setLobby(sender, args);
            case "sethub" -> setHub(sender, args);
            case "radius" -> setNumber(sender, args, 3, 100, true);
            case "slices" -> setNumber(sender, args, 2, 16, false);
            case "build" -> build(sender);
            case "createworld" -> createWorld(sender);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(settings().message("usage"));
        }
        return true;
    }

    private void start(CommandSender sender) {
        if (plugin.game().state() != GameState.WAITING) {
            sender.sendMessage(settings().message("game-running"));
        } else if (plugin.game().start(true)) {
            sender.sendMessage(settings().message("started"));
        } else {
            sender.sendMessage(settings().message("start-not-enough", Placeholder.unparsed("min", "1")));
        }
    }

    private void setCenter(CommandSender sender, String[] args) {
        if (!idle(sender) || !configEditable(sender)) {
            return;
        }
        Location location = locationFrom(sender, args);
        if (location == null) {
            return;
        }
        // From a player, the ring goes at the level of the block they stand on.
        int y = sender instanceof Player && args.length < 5 ? location.getBlockY() - 1 : location.getBlockY();
        plugin.arena().setCenter(location.getWorld().getName(), location.getBlockX(), y, location.getBlockZ());
        if (!saveArena(sender)) {
            return;
        }
        sender.sendMessage(settings().message("center-set",
                Placeholder.unparsed("world", location.getWorld().getName()),
                Placeholder.unparsed("x", String.valueOf(location.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(y)),
                Placeholder.unparsed("z", String.valueOf(location.getBlockZ()))));
    }

    private void setLobby(CommandSender sender, String[] args) {
        if (!idle(sender) || !configEditable(sender)) {
            return;
        }
        Location location = locationFrom(sender, args);
        if (location == null) {
            return;
        }
        plugin.arena().setLobby(location);
        if (saveArena(sender)) {
            sender.sendMessage(settings().message("lobby-set"));
        }
    }

    /** The hub works during games too, so it only needs a loaded config, not an idle game. */
    private void setHub(CommandSender sender, String[] args) {
        if (!configEditable(sender)) {
            return;
        }
        Location location = locationFrom(sender, args);
        if (location == null) {
            return;
        }
        plugin.hub().setLocation(location);
        sender.sendMessage(settings().message(plugin.saveHub() ? "hub-set" : "hub-not-saved"));
    }

    private void setNumber(CommandSender sender, String[] args, int min, int max, boolean radius) {
        if (!idle(sender) || !configEditable(sender)) {
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(settings().message("usage"));
            return;
        }
        Integer value = parseInt(sender, args[1]);
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            sender.sendMessage(settings().message("out-of-range",
                    Placeholder.unparsed("min", String.valueOf(min)), Placeholder.unparsed("max", String.valueOf(max))));
            return;
        }
        if (radius) {
            plugin.arena().setRadius(value);
        } else {
            plugin.arena().setSlices(value);
        }
        if (!saveArena(sender)) {
            return;
        }
        sender.sendMessage(radius
                ? settings().message("radius-set", Placeholder.unparsed("radius", String.valueOf(value)))
                : settings().message("slices-set", Placeholder.unparsed("slices", String.valueOf(value))));
    }

    private void build(CommandSender sender) {
        if (!idle(sender)) {
            return;
        }
        if (plugin.isArenaFromJar()) {
            // Building the jar-default arena would leave an orphaned ring once the real config loads.
            sender.sendMessage(settings().message("build-needs-config"));
            return;
        }
        Arena arena = plugin.arena();
        sender.sendMessage(settings().message("arena-building"));
        plugin.arenaBuilder().build(arena).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not build the arena", error);
                sender.sendMessage(settings().message("arena-build-failed",
                        Placeholder.unparsed("error", String.valueOf(error.getMessage()))));
                return;
            }
            plugin.getLogger().info("Arena built at " + arena.worldName() + " " + arena.centerX() + " "
                    + arena.centerY() + " " + arena.centerZ() + " (radius " + arena.radius() + ").");
            sender.sendMessage(settings().message("arena-built",
                    Placeholder.unparsed("radius", String.valueOf(arena.radius())),
                    Placeholder.unparsed("slices", String.valueOf(arena.slices()))));
        });
    }

    private void createWorld(CommandSender sender) {
        if (!idle(sender)) {
            return;
        }
        String name = plugin.arena().worldName();
        World world = plugin.createVoidWorld(name);
        sender.sendMessage(settings().message(world != null ? "world-created" : "world-not-found",
                Placeholder.unparsed("world", name)));
    }

    private void reload(CommandSender sender) {
        if (!idle(sender)) {
            return;
        }
        sender.sendMessage(settings().message(plugin.loadConfiguration() ? "reloaded" : "reload-failed"));
    }

    // --- Helpers -------------------------------------------------------------------

    /**
     * Arena and hub settings can't be changed after config.yml failed to load: the change could
     * never be saved and the next successful reload would throw it away, so it is refused up front.
     */
    private boolean configEditable(CommandSender sender) {
        if (plugin.isConfigSavable()) {
            return true;
        }
        sender.sendMessage(settings().message("config-load-failed"));
        return false;
    }

    /**
     * Saves the arena; on failure tells the sender and returns false so no success message follows.
     * The change stays applied and is saved with the next build once the file is fixed.
     */
    private boolean saveArena(CommandSender sender) {
        if (plugin.saveArena()) {
            return true;
        }
        sender.sendMessage(settings().message("config-invalid"));
        return false;
    }

    /** Setup changes are only allowed while nobody is playing. */
    private boolean idle(CommandSender sender) {
        if (plugin.game().state() != GameState.WAITING) {
            sender.sendMessage(settings().message("cannot-while-running"));
            return false;
        }
        if (plugin.arenaBuilder().isBusy()) {
            // A running build reads the arena live; changing or reloading it now would split the ring.
            sender.sendMessage(settings().message("arena-busy"));
            return false;
        }
        return true;
    }

    /** The sender's own location, or "world x y z" from the arguments (needed from the console). */
    private Location locationFrom(CommandSender sender, String[] args) {
        if (args.length >= 5) {
            World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                sender.sendMessage(settings().message("world-not-found", Placeholder.unparsed("world", args[1])));
                return null;
            }
            Double x = parseDouble(sender, args[2]);
            Double y = parseDouble(sender, args[3]);
            Double z = parseDouble(sender, args[4]);
            if (x == null || y == null || z == null) {
                return null;
            }
            return new Location(world, x, y, z);
        }
        if (sender instanceof Player player) {
            return player.getLocation();
        }
        sender.sendMessage(settings().message("usage"));
        return null;
    }

    private void withPlayer(CommandSender sender, Consumer<Player> action) {
        if (sender instanceof Player player) {
            action.accept(player);
        } else {
            sender.sendMessage(settings().message("players-only"));
        }
    }

    private Integer parseInt(CommandSender sender, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            sender.sendMessage(settings().message("invalid-number", Placeholder.unparsed("value", value)));
            return null;
        }
    }

    private Double parseDouble(CommandSender sender, String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            sender.sendMessage(settings().message("invalid-number", Placeholder.unparsed("value", value)));
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Stream.concat(PLAYER_SUBCOMMANDS.stream(),
                            sender.hasPermission("ringout.admin") ? ADMIN_SUBCOMMANDS.stream() : Stream.empty())
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && (Stream.of("setcenter", "setlobby", "sethub").anyMatch(args[0]::equalsIgnoreCase))) {
            return Bukkit.getWorlds().stream().map(World::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
