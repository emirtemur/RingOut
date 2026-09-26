package me.emirtemur.ringout.menu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** One entry under "items:" of a menu file: a plain item, or the arena list that repeats per arena. */
final class MenuEntry {

    enum Type { ITEM, ARENA_LIST }

    enum Click { ANY, LEFT, RIGHT }

    /** Keys of material_by_state, matching the arena states the menu can show. */
    static final List<String> STATES = List.of("waiting", "starting", "full", "building", "playing", "ending", "not_built");

    static final List<String> ACTIONS = List.of("[close]", "[join]", "[player]", "[console]", "[message]", "[sound]");

    final String id;
    final Type type;
    final Material material;
    final int amount;
    final List<Integer> slots;
    final String name;
    final List<String> lore;
    final Map<Click, List<String>> commands = new EnumMap<>(Click.class);
    // Arena list only:
    final Map<String, Material> materialByState = new LinkedHashMap<>();
    final String joinHint;
    final String notJoinableHint;
    final Material emptyMaterial;
    final String emptyName;

    private MenuEntry(String id, ConfigurationSection sec, int size, String where, Logger log) {
        this.id = id;
        this.type = "arena_list".equalsIgnoreCase(sec.getString("type", "")) ? Type.ARENA_LIST : Type.ITEM;
        this.material = material(sec.getString("material", "STONE"), Material.STONE, where + ".material", log);
        this.amount = Math.max(1, Math.min(64, sec.getInt("amount", 1)));
        this.slots = slots(sec, size, where, log);
        this.name = sec.getString("display_name", "");
        this.lore = List.copyOf(sec.getStringList("lore"));
        commands.put(Click.ANY, actions(sec, "click_commands", where, log));
        commands.put(Click.LEFT, actions(sec, "left_click_commands", where, log));
        commands.put(Click.RIGHT, actions(sec, "right_click_commands", where, log));

        ConfigurationSection states = sec.getConfigurationSection("material_by_state");
        for (String state : STATES) {
            String value = states != null ? states.getString(state) : null;
            if (value != null) {
                materialByState.put(state, material(value, material, where + ".material_by_state." + state, log));
            }
        }
        this.joinHint = sec.getString("join_hint", "<green>Click to join");
        this.notJoinableHint = sec.getString("not_joinable_hint", "<red>Can't join right now");
        this.emptyMaterial = material(sec.getString("empty.material", "BARRIER"), Material.BARRIER, where + ".empty.material", log);
        this.emptyName = sec.getString("empty.display_name", "");
    }

    static MenuEntry load(String id, ConfigurationSection sec, int size, String where, Logger log) {
        return new MenuEntry(id, sec, size, where + ".items." + id, log);
    }

    /** Commands for this click: the click-specific list first, then click_commands. */
    List<String> commandsFor(Click click) {
        List<String> result = new ArrayList<>(commands.getOrDefault(click, List.of()));
        result.addAll(commands.get(Click.ANY));
        return result;
    }

    Material materialFor(String state) {
        return materialByState.getOrDefault(state, material);
    }

    private static Material material(String name, Material fallback, String where, Logger log) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            log.warning("Invalid material '" + name + "' at " + where + ", using " + fallback.name() + ".");
            return fallback;
        }
        return material;
    }

    /** "slot: 4" or "slots: [0-8, 13, 18-26]", limited to the menu size. */
    private static List<Integer> slots(ConfigurationSection sec, int size, String where, Logger log) {
        List<Integer> result = new ArrayList<>();
        if (sec.isInt("slot")) {
            addSlot(result, sec.getInt("slot"), size, where, log);
        }
        for (Object entry : sec.getList("slots", List.of())) {
            String text = String.valueOf(entry).trim();
            try {
                int dash = text.indexOf('-', 1);
                if (dash > 0) {
                    int from = Integer.parseInt(text.substring(0, dash).trim());
                    int to = Integer.parseInt(text.substring(dash + 1).trim());
                    for (int slot = Math.min(from, to); slot <= Math.max(from, to); slot++) {
                        addSlot(result, slot, size, where, log);
                    }
                } else {
                    addSlot(result, Integer.parseInt(text), size, where, log);
                }
            } catch (NumberFormatException e) {
                log.warning("Invalid slot '" + text + "' at " + where + ", skipping it.");
            }
        }
        return List.copyOf(result);
    }

    private static void addSlot(List<Integer> result, int slot, int size, String where, Logger log) {
        if (slot < 0 || slot >= size) {
            log.warning("Slot " + slot + " at " + where + " is outside the menu (size " + size + "), skipping it.");
        } else if (!result.contains(slot)) {
            result.add(slot);
        }
    }

    private static List<String> actions(ConfigurationSection sec, String key, String where, Logger log) {
        List<String> result = new ArrayList<>();
        for (String action : sec.getStringList(key)) {
            String trimmed = action.trim();
            String tag = trimmed.contains("]") ? trimmed.substring(0, trimmed.indexOf(']') + 1).toLowerCase(Locale.ROOT) : "";
            if (!ACTIONS.contains(tag)) {
                log.warning("Unknown action '" + action + "' at " + where + "." + key + ", skipping it.");
                continue;
            }
            result.add(trimmed);
        }
        return List.copyOf(result);
    }
}
