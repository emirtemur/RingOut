package me.emirtemur.ringout.menu;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.arena.Arenas;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Every menu in plugins/RingOut/menus/, one file each, laid out like DeluxeMenus menus. */
public final class Menus {

    /** Bundled menus, copied into the menus folder when missing. */
    private static final List<String> DEFAULTS = List.of("arenas");

    private final RingOutPlugin plugin;
    private final File directory;
    private final Map<String, MenuDefinition> menus = new LinkedHashMap<>();

    public Menus(RingOutPlugin plugin) {
        this.plugin = plugin;
        this.directory = new File(plugin.getDataFolder(), "menus");
    }

    /** (Re)reads every menu file. A file that does not parse is skipped with an error. */
    public void load() {
        menus.clear();
        for (String name : DEFAULTS) {
            if (!new File(directory, name + ".yml").exists()) {
                plugin.saveResource("menus/" + name + ".yml", false);
            }
        }
        File[] files = directory.listFiles((dir, file) -> file.endsWith(".yml"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
            if (!Arenas.NAME.matcher(name).matches()) {
                plugin.getLogger().warning("Skipping menus/" + file.getName() + ": menu names may only use a-z, 0-9, _ and -.");
                continue;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                plugin.getLogger().severe("Menu " + name + " is not loaded because " + file.getName()
                        + " has an error: " + e.getMessage());
                continue;
            }
            menus.put(name, MenuDefinition.load(name, yaml, plugin.getLogger()));
        }
    }

    /** Closes every open menu, so nobody keeps clicking a layout that was just reloaded. */
    public void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof OpenMenu) {
                player.closeInventory();
            }
        }
    }

    public List<String> names() {
        return List.copyOf(menus.keySet());
    }

    /** Opens the menu; false when there is no such menu (missing, or its file is broken). */
    public boolean open(Player player, String name) {
        MenuDefinition definition = menus.get(name.toLowerCase(Locale.ROOT));
        if (definition == null) {
            return false;
        }
        player.openInventory(new OpenMenu(plugin, definition, player).getInventory());
        return true;
    }
}
