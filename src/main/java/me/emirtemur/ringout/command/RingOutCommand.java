package me.emirtemur.ringout.command;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.arena.Arena;
import me.emirtemur.ringout.arena.Arenas;
import me.emirtemur.ringout.config.Settings;
import me.emirtemur.ringout.game.Game;
import me.emirtemur.ringout.game.GameState;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class RingOutCommand implements TabExecutor {

    private static final List<String> PLAYER_SUBCOMMANDS = List.of("join", "leave", "list", "menu");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of(
            "create", "delete", "setcenter", "setlobby", "radius", "slices", "build", "start", "stop",
            "sethub", "createworld", "reload");
    /** Subcommands whose second argument is an arena name. */
    private static final List<String> ARENA_SUBCOMMANDS = List.of(
            "join", "delete", "setcenter", "setlobby", "radius", "slices", "build", "start", "stop");
    private static final String DEFAULT_WORLD = "ringout_world";

    private final RingOutPlugin plugin;

    public RingOutCommand(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Arenas arenas() {
        return plugin.arenas();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(settings().message("usage"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (ADMIN_SUBCOMMANDS.contains(sub) && !sender.hasPermission("ringout.admin")) {
            sender.sendMessage(settings().message("no-permission"));
            return true;
        }
        if (PLAYER_SUBCOMMANDS.contains(sub) && !sender.hasPermission("ringout.play")) {
            sender.sendMessage(settings().message("no-permission"));
            return true;
        }
        switch (sub) {
            case "join" -> join(sender, args);
            case "leave" -> withPlayer(sender, this::leave);
            case "list" -> list(sender);
            case "menu" -> menu(sender, args);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "setcenter" -> setCenter(sender, args);
            case "setlobby" -> setLobby(sender, args);
            case "radius" -> setNumber(sender, args, 3, 100, true);
            case "slices" -> setNumber(sender, args, 2, 16, false);
            case "build" -> build(sender, args);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "sethub" -> setHub(sender, args);
            case "createworld" -> createWorld(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(settings().message("usage"));
        }
        return true;
    }

    // --- Players ---------------------------------------------------------------

    private void join(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(settings().message("players-only"));
            return;
        }
        Game game = arenaArg(sender, args);
        if (game != null) {
            game.join(player);
        }
    }

    private void leave(Player player) {
        Game game = arenas().gameOf(player);
        if (game == null) {
            player.sendMessage(settings().message("not-in-game"));
        } else {
            game.leave(player);
        }
    }

    /** Opens a menu from plugins/RingOut/menus (default: the one the hub compass opens). */
    private void menu(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(settings().message("players-only"));
            return;
        }
        if (arenas().gameOf(player) != null) {
            player.sendMessage(settings().message("already-in-game"));
            return;
        }
        String name = args.length >= 2 ? args[1] : settings().hubMenu;
        if (!plugin.menus().open(player, name)) {
            player.sendMessage(settings().message("menu-not-found", Placeholder.unparsed("menu", name)));
        }
    }

    private void list(CommandSender sender) {
        if (arenas().games().isEmpty()) {
            sender.sendMessage(settings().message("arena-list-empty"));
            return;
        }
        sender.sendMessage(settings().message("arena-list-header"));
        for (Game game : arenas().games()) {
            Arena arena = game.arena();
            sender.sendMessage(settings().text("arena-list-entry", arenaTag(arena),
                    Placeholder.component("state", settings().text(stateKey(game))),
                    Placeholder.unparsed("count", String.valueOf(game.playerCount())),
                    Placeholder.unparsed("max", String.valueOf(settings().maxPlayers)),
                    Placeholder.unparsed("world", arena.worldName()),
                    Placeholder.unparsed("x", String.valueOf(arena.centerX())),
                    Placeholder.unparsed("y", String.valueOf(arena.centerY())),
                    Placeholder.unparsed("z", String.valueOf(arena.centerZ())),
                    Placeholder.unparsed("radius", String.valueOf(arena.radius()))));
        }
    }

    /** Message key describing the arena's state, shared with the arena menu. */
    public static String stateKey(Game game) {
        if (!game.arena().isBuilt()) {
            return "state-not-built";
        }
        return switch (game.state()) {
            case WAITING -> game.isStarting() ? "state-starting" : "state-waiting";
            case BUILDING -> "state-building";
            case COUNTDOWN, ACTIVE -> "state-playing";
            case ENDING -> "state-ending";
        };
    }

    // --- Arena setup ---------------------------------------------------------------

    private void create(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(settings().message("usage"));
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!Arenas.NAME.matcher(name).matches()) {
            sender.sendMessage(settings().message("invalid-arena-name"));
            return;
        }
        if (arenas().game(name) != null) {
            sender.sendMessage(settings().message("arena-exists", Placeholder.unparsed("arena", name)));
            return;
        }
        Location location = locationFrom(sender, args, 2);
        if (location == null) {
            return;
        }
        int y = ringY(sender, args, 2, location);
        int radius = Math.max(3, Math.min(100, settings().arenaDefaults.getInt("radius", 20)));
        if (overlapRefused(sender, null, location.getWorld().getName(), location.getBlockX(), location.getBlockZ(), radius)) {
            return;
        }
        Arena arena = Arena.create(name, location.getWorld().getName(), location.getBlockX(), y, location.getBlockZ(),
                settings().arenaDefaults, plugin.getLogger());
        if (arenas().create(arena) == null) {
            sender.sendMessage(settings().message("arena-create-failed", Placeholder.unparsed("arena", name)));
            return;
        }
        sender.sendMessage(settings().message("arena-created", arenaTag(arena), locationTags(arena)));
    }

    private void delete(CommandSender sender, String[] args) {
        Game game = arenaArg(sender, args);
        if (game == null || !idle(sender, game)) {
            return;
        }
        if (!game.isIdle()) {
            sender.sendMessage(settings().message("cannot-while-running"));
            return;
        }
        boolean deleted = arenas().delete(game);
        sender.sendMessage(settings().message(deleted ? "arena-deleted" : "arena-delete-failed", arenaTag(game.arena())));
    }

    private void setCenter(CommandSender sender, String[] args) {
        Game game = arenaArg(sender, args);
        if (game == null || !idle(sender, game)) {
            return;
        }
        Location location = locationFrom(sender, args, 2);
        if (location == null) {
            return;
        }
        Arena arena = game.arena();
        int y = ringY(sender, args, 2, location);
        String world = location.getWorld().getName();
        if (overlapRefused(sender, arena, world, location.getBlockX(), location.getBlockZ(), arena.radius())) {
            return;
        }
        arena.setCenter(world, location.getBlockX(), y, location.getBlockZ());
        if (saveArena(sender, arena)) {
            sender.sendMessage(settings().message("center-set", arenaTag(arena), locationTags(arena)));
        }
    }

    private void setLobby(CommandSender sender, String[] args) {
        Game game = arenaArg(sender, args);
        if (game == null || !idle(sender, game)) {
            return;
        }
        Location location = locationFrom(sender, args, 2);
        if (location == null) {
            return;
        }
        game.arena().setLobby(location);
        if (saveArena(sender, game.arena())) {
            sender.sendMessage(settings().message("lobby-set", arenaTag(game.arena())));
        }
    }

    private void setNumber(CommandSender sender, String[] args, int min, int max, boolean radius) {
        Game game = arenaArg(sender, args);
        if (game == null || !idle(sender, game)) {
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(settings().message("usage"));
            return;
        }
        Integer value = parseInt(sender, args[2]);
        if (value == null) {
            return;
        }
        if (value < min || value > max) {
            sender.sendMessage(settings().message("out-of-range",
                    Placeholder.unparsed("min", String.valueOf(min)), Placeholder.unparsed("max", String.valueOf(max))));
            return;
        }
        Arena arena = game.arena();
        if (radius) {
            if (overlapRefused(sender, arena, arena.worldName(), arena.centerX(), arena.centerZ(), value)) {
                return;
            }
            arena.setRadius(value);
        } else {
            arena.setSlices(value);
        }
        if (!saveArena(sender, arena)) {
            return;
        }
        sender.sendMessage(radius
                ? settings().message("radius-set", arenaTag(arena), Placeholder.unparsed("radius", String.valueOf(value)))
                : settings().message("slices-set", arenaTag(arena), Placeholder.unparsed("slices", String.valueOf(value))));
    }

    private void build(CommandSender sender, String[] args) {
        Game game = arenaArg(sender, args);
        if (game == null || !idle(sender, game)) {
            return;
        }
        Arena arena = game.arena();
        sender.sendMessage(settings().message("arena-building", arenaTag(arena)));
        plugin.arenaBuilder().build(arena).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not build arena " + arena.name(), error);
                sender.sendMessage(settings().message("arena-build-failed", arenaTag(arena),
                        Placeholder.unparsed("error", String.valueOf(error.getMessage()))));
                return;
            }
            plugin.getLogger().info("Arena " + arena.name() + " built at " + arena.worldName() + " " + arena.centerX()
                    + " " + arena.centerY() + " " + arena.centerZ() + " (radius " + arena.radius() + ").");
            sender.sendMessage(settings().message("arena-built", arenaTag(arena),
                    Placeholder.unparsed("radius", String.valueOf(arena.radius())),
                    Placeholder.unparsed("slices", String.valueOf(arena.slices()))));
        });
    }

    private void start(CommandSender sender, String[] args) {
        Game game = arenaArg(sender, args);
        if (game == null) {
            return;
        }
        if (game.state() != GameState.WAITING) {
            sender.sendMessage(settings().message("game-running"));
        } else if (game.start(true)) {
            sender.sendMessage(settings().message("started", arenaTag(game.arena())));
        } else {
            sender.sendMessage(settings().message("start-not-enough", Placeholder.unparsed("min", "1")));
        }
    }

    private void stop(CommandSender sender, String[] args) {
        Game game = arenaArg(sender, args);
        if (game != null) {
            sender.sendMessage(settings().message(game.stop() ? "stopped" : "no-game", arenaTag(game.arena())));
        }
    }

    /** The hub works during games too, so it only needs a loaded config, not idle arenas. */
    private void setHub(CommandSender sender, String[] args) {
        if (!plugin.isConfigSavable()) {
            sender.sendMessage(settings().message("config-load-failed"));
            return;
        }
        Location location = locationFrom(sender, args, 1);
        if (location == null) {
            return;
        }
        plugin.hub().setLocation(location);
        sender.sendMessage(settings().message(plugin.saveHub() ? "hub-set" : "hub-not-saved"));
    }

    private void createWorld(CommandSender sender, String[] args) {
        String name = args.length >= 2 ? args[1] : DEFAULT_WORLD;
        boolean alreadyLoaded = Bukkit.getWorld(name) != null;
        World world = plugin.createVoidWorld(name);
        String key = world != null ? "world-created" : alreadyLoaded ? "world-not-void" : "world-not-found";
        sender.sendMessage(settings().message(key, Placeholder.unparsed("world", name)));
    }

    private void reload(CommandSender sender) {
        if (!arenas().allIdle()) {
            sender.sendMessage(settings().message("reload-busy"));
            return;
        }
        sender.sendMessage(settings().message(plugin.reloadAll() ? "reloaded" : "reload-failed",
                Placeholder.unparsed("count", String.valueOf(arenas().games().size()))));
    }

    // --- Helpers -------------------------------------------------------------------

    /** The arena named by args[1], telling the sender when it is missing. */
    private Game arenaArg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(settings().message("usage"));
            return null;
        }
        Game game = arenas().game(args[1]);
        if (game == null) {
            sender.sendMessage(settings().message("arena-not-found", Placeholder.unparsed("arena", args[1])));
        }
        return game;
    }

    /** Setup changes are only allowed while the arena has no match running and is not being built. */
    private boolean idle(CommandSender sender, Game game) {
        if (game.state() != GameState.WAITING) {
            sender.sendMessage(settings().message("cannot-while-running"));
            return false;
        }
        if (plugin.arenaBuilder().isBusy(game.arena())) {
            // A running build reads the arena live; changing it now would split the ring.
            sender.sendMessage(settings().message("arena-busy", arenaTag(game.arena())));
            return false;
        }
        return true;
    }

    private boolean overlapRefused(CommandSender sender, Arena self, String world, int x, int z, int radius) {
        Arena other = arenas().overlapping(self, world, x, z, radius);
        if (other == null) {
            return false;
        }
        sender.sendMessage(settings().message("arena-overlap", Placeholder.unparsed("other", other.name()),
                Placeholder.unparsed("gap", String.valueOf(Arenas.GAP))));
        return true;
    }

    /**
     * Saves the arena; on failure tells the sender and returns false so no success message follows.
     * The change stays applied and is saved with the next build once the file is fixed.
     */
    private boolean saveArena(CommandSender sender, Arena arena) {
        if (arenas().save(arena)) {
            return true;
        }
        sender.sendMessage(settings().message("arena-not-saved", arenaTag(arena)));
        return false;
    }

    /** From a player the ring goes at the level of the block they stand on; from coordinates, at that y. */
    private static int ringY(CommandSender sender, String[] args, int offset, Location location) {
        boolean ownPosition = sender instanceof Player && args.length < offset + 4;
        return ownPosition ? location.getBlockY() - 1 : location.getBlockY();
    }

    /** The sender's own location, or "world x y z" starting at args[offset] (needed from the console). */
    private Location locationFrom(CommandSender sender, String[] args, int offset) {
        if (args.length >= offset + 4) {
            World world = Bukkit.getWorld(args[offset]);
            if (world == null) {
                sender.sendMessage(settings().message("world-not-found", Placeholder.unparsed("world", args[offset])));
                return null;
            }
            Double x = parseDouble(sender, args[offset + 1]);
            Double y = parseDouble(sender, args[offset + 2]);
            Double z = parseDouble(sender, args[offset + 3]);
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

    private static TagResolver arenaTag(Arena arena) {
        return Placeholder.unparsed("arena", arena.name());
    }

    private static TagResolver locationTags(Arena arena) {
        return TagResolver.resolver(
                Placeholder.unparsed("world", arena.worldName()),
                Placeholder.unparsed("x", String.valueOf(arena.centerX())),
                Placeholder.unparsed("y", String.valueOf(arena.centerY())),
                Placeholder.unparsed("z", String.valueOf(arena.centerZ())));
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
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            return filter(Stream.concat(PLAYER_SUBCOMMANDS.stream(),
                    sender.hasPermission("ringout.admin") ? ADMIN_SUBCOMMANDS.stream() : Stream.empty()), args[0]);
        }
        if (args.length == 2 && ARENA_SUBCOMMANDS.contains(sub)) {
            return filter(arenas().names().stream(), args[1]);
        }
        if (args.length == 2 && sub.equals("menu")) {
            return filter(plugin.menus().names().stream(), args[1]);
        }
        boolean worldArg = (args.length == 2 && (sub.equals("sethub") || sub.equals("createworld")))
                || (args.length == 3 && (sub.equals("create") || sub.equals("setcenter") || sub.equals("setlobby")));
        if (worldArg) {
            return filter(Bukkit.getWorlds().stream().map(World::getName), args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
