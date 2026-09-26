package me.emirtemur.ringout.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;

/** A parsed menus/&lt;name&gt;.yml file. */
final class MenuDefinition {

    final String name;
    final String title;
    final int size;
    final int updateInterval;
    final List<MenuEntry> entries;

    private MenuDefinition(String name, String title, int size, int updateInterval, List<MenuEntry> entries) {
        this.name = name;
        this.title = title;
        this.size = size;
        this.updateInterval = updateInterval;
        this.entries = entries;
    }

    static MenuDefinition load(String name, ConfigurationSection root, Logger log) {
        String where = "menus/" + name + ".yml";
        int requested = root.getInt("size", 27);
        int size = Math.max(9, Math.min(54, ((requested + 8) / 9) * 9));
        if (size != requested) {
            log.warning(where + ": size " + requested + " is not 9 to 54 in steps of 9, using " + size + ".");
        }
        List<MenuEntry> entries = new ArrayList<>();
        ConfigurationSection items = root.getConfigurationSection("items");
        if (items != null) {
            for (String id : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(id);
                if (item == null) {
                    log.warning(where + ": items." + id + " is not a section, skipping it.");
                    continue;
                }
                entries.add(MenuEntry.load(id, item, size, where, log));
            }
        }
        return new MenuDefinition(name, root.getString("menu_title", ""), size,
                Math.max(0, root.getInt("update_interval", 1)), List.copyOf(entries));
    }
}
