package me.emirtemur.ringout.item;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import me.emirtemur.ringout.config.PluginConfig.ItemEntry;
import me.emirtemur.ringout.config.Settings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Weighted pool of random items handed out during a game. */
public final class ItemPool {

    private record Entry(List<ItemStack> items, int weight) {
    }

    private final List<Entry> entries;
    private final int totalWeight;

    private ItemPool(List<Entry> entries) {
        this.entries = entries;
        this.totalWeight = entries.stream().mapToInt(Entry::weight).sum();
    }

    public static ItemPool load(List<ItemEntry> raw, Logger log) {
        List<Entry> entries = new ArrayList<>();
        List<ItemEntry> list = raw != null ? raw : List.of();
        for (int i = 0; i < list.size(); i++) {
            ItemEntry config = list.get(i);
            String where = "items[" + i + "]";
            ItemStack main = config != null ? parseItem(config, log, where) : null;
            if (main == null) {
                continue;
            }
            List<ItemStack> items = new ArrayList<>();
            items.add(main);
            List<ItemEntry> extras = config.extra != null ? config.extra : List.of();
            for (int j = 0; j < extras.size(); j++) {
                ItemStack extra = extras.get(j) != null ? parseItem(extras.get(j), log, where + ".extra[" + j + "]") : null;
                if (extra != null) {
                    items.add(extra);
                }
            }
            entries.add(new Entry(List.copyOf(items), Math.max(1, config.weight)));
        }
        if (entries.isEmpty()) {
            log.warning("The item pool is empty, players will not receive items.");
        }
        return new ItemPool(entries);
    }

    /** A fresh copy of a random entry's items, or an empty list if the pool is empty. */
    public List<ItemStack> roll() {
        if (totalWeight <= 0) {
            return List.of();
        }
        int pick = ThreadLocalRandom.current().nextInt(totalWeight);
        for (Entry entry : entries) {
            pick -= entry.weight();
            if (pick < 0) {
                return entry.items().stream().map(ItemStack::clone).toList();
            }
        }
        return List.of();
    }

    public int size() {
        return entries.size();
    }

    private static ItemStack parseItem(ItemEntry config, Logger log, String where) {
        Material material = config.material == null ? null : Material.matchMaterial(config.material);
        if (material == null || !material.isItem() || material.isAir()) {
            log.warning("Invalid material '" + config.material + "' in " + where + ", skipping it.");
            return null;
        }
        int amount = Math.max(1, Math.min(material.getMaxStackSize(), config.amount));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        if (config.name != null && !config.name.isEmpty()) {
            meta.displayName(noItalic(Settings.mini().deserialize(config.name)));
        }
        if (config.lore != null && !config.lore.isEmpty()) {
            meta.lore(config.lore.stream().map(line -> noItalic(Settings.mini().deserialize(line))).toList());
        }
        if (config.unbreakable) {
            meta.setUnbreakable(true);
        }
        if (config.enchantments != null) {
            for (Map.Entry<String, Integer> e : config.enchantments.entrySet()) {
                Enchantment enchantment = enchantment(e.getKey());
                if (enchantment == null) {
                    log.warning("Unknown enchantment '" + e.getKey() + "' in " + where + ", skipping it.");
                    continue;
                }
                meta.addEnchant(enchantment, Math.max(1, e.getValue() != null ? e.getValue() : 1), true);
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private static Enchantment enchantment(String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase());
        if (key == null) {
            return null;
        }
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key);
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
