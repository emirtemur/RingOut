package me.emirtemur.ringout.item;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
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

    public static ItemPool load(List<Map<?, ?>> raw, Logger log) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<?, ?> map = raw.get(i);
            String where = "items[" + i + "]";
            ItemStack main = parseItem(map, log, where);
            if (main == null) {
                continue;
            }
            List<ItemStack> items = new ArrayList<>();
            items.add(main);
            if (map.get("extra") instanceof List<?> extras) {
                for (int j = 0; j < extras.size(); j++) {
                    if (extras.get(j) instanceof Map<?, ?> extraMap) {
                        ItemStack extra = parseItem(extraMap, log, where + ".extra[" + j + "]");
                        if (extra != null) {
                            items.add(extra);
                        }
                    }
                }
            }
            int weight = Math.max(1, number(map.get("weight"), 1));
            entries.add(new Entry(List.copyOf(items), weight));
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

    private static ItemStack parseItem(Map<?, ?> map, Logger log, String where) {
        Object materialName = map.get("material");
        Material material = materialName == null ? null : Material.matchMaterial(materialName.toString());
        if (material == null || !material.isItem() || material.isAir()) {
            log.warning("Invalid material '" + materialName + "' in " + where + ", skipping it.");
            return null;
        }
        int amount = Math.max(1, Math.min(material.getMaxStackSize(), number(map.get("amount"), 1)));
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        if (map.get("name") instanceof String name) {
            meta.displayName(noItalic(Settings.mini().deserialize(name)));
        }
        if (map.get("lore") instanceof List<?> lore) {
            meta.lore(lore.stream().map(line -> noItalic(Settings.mini().deserialize(String.valueOf(line)))).toList());
        }
        if (Boolean.TRUE.equals(map.get("unbreakable"))) {
            meta.setUnbreakable(true);
        }
        if (map.get("enchantments") instanceof Map<?, ?> enchantments) {
            for (Map.Entry<?, ?> e : enchantments.entrySet()) {
                Enchantment enchantment = enchantment(String.valueOf(e.getKey()));
                if (enchantment == null) {
                    log.warning("Unknown enchantment '" + e.getKey() + "' in " + where + ", skipping it.");
                    continue;
                }
                meta.addEnchant(enchantment, Math.max(1, number(e.getValue(), 1)), true);
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

    private static int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
