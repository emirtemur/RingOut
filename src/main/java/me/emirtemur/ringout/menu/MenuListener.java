package me.emirtemur.ringout.menu;

import java.util.List;
import java.util.Locale;
import me.emirtemur.ringout.RingOutPlugin;
import me.emirtemur.ringout.game.Game;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Opens the menu from the hub compass, runs click commands in menus and keeps the compass in place. */
public final class MenuListener implements Listener {

    private final RingOutPlugin plugin;

    public MenuListener(RingOutPlugin plugin) {
        this.plugin = plugin;
    }

    private MenuItem menuItem() {
        return plugin.menuItem();
    }

    /** Run once a second: open menus redraw on their own update interval. */
    public void tickOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof OpenMenu menu) {
                menu.tick();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !menuItem().is(event.getItem())) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (plugin.arenas().gameOf(player) == null) {
            openHubMenu(player);
        }
    }

    /**
     * Right-clicking an entity (an item frame, an allay) skips PlayerInteractEvent. Without this the
     * compass could be handed away; instead it opens the menu there too.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUseOnEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!menuItem().is(player.getInventory().getItem(event.getHand()))) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND && plugin.arenas().gameOf(player) == null) {
            openHubMenu(player);
        }
    }

    /** Armor stands fire this separate event first; it has its own handler list, so cancel it too. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUseAtEntity(PlayerInteractAtEntityEvent event) {
        if (menuItem().is(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    private void openHubMenu(Player player) {
        String menu = plugin.settings().hubMenu;
        if (!plugin.menus().open(player, menu)) {
            player.sendMessage(plugin.settings().message("menu-not-found", Placeholder.unparsed("menu", menu)));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof OpenMenu menu) {
            // Nothing moves in or out of a menu, from either side.
            event.setCancelled(true);
            if (event.getClickedInventory() == menu.getInventory()) {
                MenuEntry.Click click = event.isLeftClick() ? MenuEntry.Click.LEFT
                        : event.isRightClick() ? MenuEntry.Click.RIGHT : MenuEntry.Click.ANY;
                List<String> commands = menu.commands(event.getSlot(), click);
                String arena = menu.arenaAt(event.getSlot());
                if (!commands.isEmpty()) {
                    // Next tick: closing or teleporting inside an inventory click is not safe.
                    Bukkit.getScheduler().runTask(plugin, () -> run(menu.viewer(), commands, arena));
                }
            }
            return;
        }
        if (touchesMenuItem(event)) {
            event.setCancelled(true);
        }
    }

    private void run(Player player, List<String> commands, String arena) {
        if (!player.isOnline()) {
            return;
        }
        for (String command : commands) {
            String argument = OpenMenu.argument(command);
            switch (OpenMenu.tag(command)) {
                case "[close]" -> player.closeInventory();
                case "[join]" -> join(player, arena);
                case "[player]" -> player.performCommand(argument);
                case "[console]" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), argument);
                case "[message]" -> player.sendMessage(OpenMenu.parse(argument));
                case "[sound]" -> playSound(player, argument);
                default -> {
                    // Unknown actions are dropped with a warning when the menu loads.
                }
            }
        }
    }

    private void join(Player player, String arena) {
        Game game = arena != null ? plugin.arenas().game(arena) : null;
        player.closeInventory();
        if (game != null && plugin.arenas().gameOf(player) == null) {
            game.join(player);
        }
    }

    /** "[sound] ui.button.click 1 1": key, then optional volume and pitch. */
    private void playSound(Player player, String argument) {
        String[] parts = argument.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return;
        }
        float volume = parts.length > 1 ? parseFloat(parts[1]) : 1f;
        float pitch = parts.length > 2 ? parseFloat(parts[2]) : 1f;
        player.playSound(player.getLocation(), parts[0].toLowerCase(Locale.ROOT), volume, pitch);
    }

    private static float parseFloat(String value) {
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private boolean touchesMenuItem(InventoryClickEvent event) {
        if (menuItem().is(event.getCurrentItem()) || menuItem().is(event.getCursor())) {
            return true;
        }
        if (event.getClick() == ClickType.NUMBER_KEY && event.getWhoClicked() instanceof Player player) {
            return menuItem().is(player.getInventory().getItem(event.getHotbarButton()));
        }
        // Swapping with the off hand (F) inside an inventory.
        return event.getClick() == ClickType.SWAP_OFFHAND && event.getWhoClicked() instanceof Player player
                && menuItem().is(player.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof OpenMenu
                || menuItem().is(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent event) {
        if (menuItem().is(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (menuItem().is(event.getMainHandItem()) || menuItem().is(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }
}
