package me.emirtemur.ringout.menu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.command.RingOutCommand;
import me.emirtemur.ringout.config.Settings;
import me.emirtemur.ringout.game.Game;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** A menu one player has open. Knows what every slot does and redraws itself on its update interval. */
public final class OpenMenu implements InventoryHolder {

    /** What a click on a slot runs, and for arena icons which arena it was. */
    private record Slot(MenuEntry entry, String arena) {
    }

    /** What Paper accepts as a player name by default. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final RingOutPlugin plugin;
    private final MenuDefinition definition;
    private final Player viewer;
    private final Inventory inventory;
    private final Map<Integer, Slot> slots = new HashMap<>();
    private int secondsSinceRedraw;

    OpenMenu(RingOutPlugin plugin, MenuDefinition definition, Player viewer) {
        this.plugin = plugin;
        this.definition = definition;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, definition.size,
                Settings.mini().deserialize(fill(definition.title, null)));
        redraw();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Called once a second; redraws when the menu's update interval has passed. */
    void tick() {
        if (definition.updateInterval > 0 && ++secondsSinceRedraw >= definition.updateInterval) {
            redraw();
        }
    }

    void redraw() {
        secondsSinceRedraw = 0;
        inventory.clear();
        slots.clear();
        for (MenuEntry entry : definition.entries) {
            if (entry.type == MenuEntry.Type.ARENA_LIST) {
                drawArenas(entry);
            } else {
                for (int slot : entry.slots) {
                    inventory.setItem(slot, item(entry.material, entry.amount, fill(entry.name, null), fillAll(entry.lore, null)));
                    slots.put(slot, new Slot(entry, null));
                }
            }
        }
    }

    private void drawArenas(MenuEntry entry) {
        Iterator<Integer> free = entry.slots.iterator();
        List<Game> games = new ArrayList<>(plugin.arenas().games());
        if (games.isEmpty() && free.hasNext()) {
            int slot = free.next();
            inventory.setItem(slot, item(entry.emptyMaterial, 1, fill(entry.emptyName, null), List.of()));
            slots.remove(slot);
            return;
        }
        for (Game game : games) {
            if (!free.hasNext()) {
                break;
            }
            int slot = free.next();
            String state = menuState(game);
            Material material = entry.materialFor(state);
            int count = Math.max(1, Math.min(64, game.playerCount()));
            inventory.setItem(slot, item(material, count, fill(entry.name, game), fillAll(entry.lore, game, entry)));
            slots.put(slot, new Slot(entry, game.arena().name()));
        }
    }

    /** The material_by_state key for the arena. */
    private static String menuState(Game game) {
        String key = RingOutCommand.stateKey(game).substring("state-".length()).replace('-', '_');
        if (key.equals("waiting") || key.equals("starting")) {
            return game.isJoinable() ? key : "full";
        }
        return key;
    }

    /** The commands to run for a click on this slot, with placeholders filled. Empty for empty slots. */
    List<String> commands(int slot, MenuEntry.Click click) {
        Slot target = slots.get(slot);
        if (target == null) {
            return List.of();
        }
        Game game = target.arena() != null ? plugin.arenas().game(target.arena()) : null;
        List<String> result = new ArrayList<>();
        for (String command : target.entry().commandsFor(click)) {
            if (unsafeName(command)) {
                plugin.getLogger().warning("Skipping '" + command + "' in menu " + definition.name
                        + ": the name of " + viewer.getUniqueId() + " is not a plain Minecraft name.");
                continue;
            }
            result.add(fill(command, game));
        }
        return result;
    }

    /**
     * Paper only lets [A-Za-z0-9_] names in by default, but a proxy or a disabled check could pass
     * anything; such a name must never be pasted into a command as extra arguments.
     */
    private boolean unsafeName(String command) {
        String tag = tag(command);
        return (tag.equals("[console]") || tag.equals("[player]")) && command.contains("%player%")
                && !PLAIN_NAME.matcher(viewer.getName()).matches();
    }

    /** The arena of an arena_list slot, or null. */
    String arenaAt(int slot) {
        Slot target = slots.get(slot);
        return target != null ? target.arena() : null;
    }

    Player viewer() {
        return viewer;
    }

    private String fill(String text, Game game) {
        // Escaped, so an unusual name can never add MiniMessage tags; a no-op for plain names.
        String result = text.replace("%player%", Settings.mini().escapeTags(viewer.getName()));
        if (game != null) {
            Settings s = plugin.settings();
            result = result.replace("%arena%", game.arena().name())
                    .replace("%state%", s.raw(RingOutCommand.stateKey(game)))
                    .replace("%players%", String.valueOf(game.playerCount()))
                    .replace("%max%", String.valueOf(s.maxPlayers));
        }
        return result;
    }

    private List<String> fillAll(List<String> lines, Game game) {
        return lines.stream().map(line -> fill(line, game)).toList();
    }

    private List<String> fillAll(List<String> lines, Game game, MenuEntry entry) {
        String hint = game.isJoinable() ? entry.joinHint : entry.notJoinableHint;
        return lines.stream().map(line -> fill(line.replace("%join_hint%", hint), game)).toList();
    }

    private static ItemStack item(Material material, int amount, String name, List<String> lore) {
        // Unstackable icons (a sword, a totem) can only show 1.
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MenuItem.noItalic(Settings.mini().deserialize(name)));
            meta.lore(lore.stream().map(line -> MenuItem.noItalic(Settings.mini().deserialize(line))).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The action tag of a command, e.g. "[close]". */
    static String tag(String command) {
        int end = command.indexOf(']');
        return end > 0 ? command.substring(0, end + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String argument(String command) {
        int end = command.indexOf(']');
        return end > 0 ? command.substring(end + 1).trim() : "";
    }

    static Component parse(String text) {
        return Settings.mini().deserialize(text);
    }
}
