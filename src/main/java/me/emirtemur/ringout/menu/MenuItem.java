package me.emirtemur.ringout.menu;

import java.util.List;
import me.emirtemur.ringout.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** The compass players get in the hub; right-clicking it opens the arena menu. */
public final class MenuItem {

    private final NamespacedKey key;

    public MenuItem(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "arena-menu");
    }

    public ItemStack create(Settings settings) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(settings.text("menu-item-name")));
        meta.lore(List.of(noItalic(settings.text("menu-item-lore"))));
        // Marked, so the item is recognised by tag and not by its (configurable) name.
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean is(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
