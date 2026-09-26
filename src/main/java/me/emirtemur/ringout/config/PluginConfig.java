package me.emirtemur.ringout.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;
import eu.okaeri.configs.annotation.NameModifier;
import eu.okaeri.configs.annotation.NameStrategy;
import eu.okaeri.configs.annotation.Names;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** plugins/RingOut/config.yml (texts are in messages.yml). Missing keys are filled in with these defaults on load. */
@Header({
        "RingOut - last one standing inside the ring wins.",
        "Texts use MiniMessage: https://docs.advntr.dev/minimessage/format.html"
})
@SuppressWarnings("deprecation")
@Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
public class PluginConfig extends OkaeriConfig {

    @Comment({
            "Each arena lives in plugins/RingOut/arenas/<name>.yml and is made with /ro create <name>.",
            "New arenas start with these settings; after that every arena file can be tuned on its own."
    })
    public ArenaDefaults arenaDefaults = new ArenaDefaults();

    @Comment({
            "Players who are not in a game wait at the hub. The whole server is RingOut: everyone is",
            "sent here with an empty inventory when they join the server or a game ends, except players",
            "with the ringout.bypass permission, who keep their state on join (for building)."
    })
    public Hub hub = new Hub();

    public Game game = new Game();

    public SuddenDeath suddenDeath = new SuddenDeath();

    @Comment({"Console commands run for the winner. %player% is replaced with the winner's name.",
            "Example: give %player% diamond 1"})
    public List<String> winCommands = new ArrayList<>();

    @Comment({
            "Random item pool. Each entry: material, amount, weight (higher = more common),",
            "optional name, lore, enchantments, unbreakable, and extra (items given together with it)."
    })
    public List<ItemEntry> items = ItemEntry.defaults();

    @SuppressWarnings("deprecation")
    @Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
    public static class ArenaDefaults extends OkaeriConfig {
        public int radius = 20;
        public int slices = 8;
        @Comment("Slice colors, used in order and repeated if there are more slices than colors.")
        public List<String> colors = new ArrayList<>(List.of("RED_CONCRETE", "ORANGE_CONCRETE", "YELLOW_CONCRETE",
                "LIME_CONCRETE", "CYAN_CONCRETE", "BLUE_CONCRETE", "PURPLE_CONCRETE", "PINK_CONCRETE"));
        public String edge = "WHITE_CONCRETE";
        public String core = "OBSIDIAN";
        public int coreRadius = 2;
    }

    @SuppressWarnings("deprecation")
    @Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
    public static class Hub extends OkaeriConfig {
        @Comment("Hotbar slot (0-8) of the compass that opens the arena menu.")
        public int menuSlot = 4;
        @Comment("Menu the compass opens, a file in plugins/RingOut/menus/ without .yml.")
        public String menu = "arenas";
        @Comment("Set with /ro sethub. Until then players are sent to the main world spawn.")
        public boolean set = false;
        public String world = "";
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
    }

    @SuppressWarnings("deprecation")
    @Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
    public static class Game extends OkaeriConfig {
        public int minPlayers = 2;
        public int maxPlayers = 16;
        @Comment("Start automatically once min-players have joined.")
        public boolean autoStart = true;
        @Comment("Seconds the lobby waits for more players after min-players is reached.")
        public int lobbyWait = 15;
        @Comment("Seconds players stand frozen on their slice before the fight starts.")
        public int countdown = 5;
        @Comment("Seconds between random items.")
        public int itemInterval = 8;
        @Comment("Extra distance (blocks) past the ring edge before a player counts as outside.")
        public double margin = 0.5;
        @Comment("Ticks a player may stay outside the ring (e.g. on a bridge) before elimination. 20 ticks = 1 second.")
        public int outsideGraceTicks = 20;
        @Comment("Players this many blocks below the ring are eliminated.")
        public int fallDepth = 5;
        @Comment("false = hits deal no damage but still knock back.")
        public boolean pvpDamage = true;
        @Comment("Whether TNT and other explosions can blow holes in the ring. The ring is rebuilt every game.")
        public boolean explosionsBreakArena = true;
        @Comment("Placed TNT ignites immediately.")
        public boolean autoPrimeTnt = true;
        public int tntFuseTicks = 40;
        @Comment("Spectators further than this from the center are pulled back. 0 = radius + 30.")
        public int spectatorMaxDistance = 0;
        @Comment("Seconds to celebrate before everyone is sent back.")
        public int endingSeconds = 8;
        @Comment("Blocks placed per tick while building a ring. Lower it if building causes lag.")
        public int blocksPerTick = 2000;
    }

    @SuppressWarnings("deprecation")
    @Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
    public static class SuddenDeath extends OkaeriConfig {
        public boolean enabled = true;
        @Comment("Seconds into the fight before the ring starts shrinking.")
        public int startAfter = 300;
        @Comment("Seconds between each ring removal.")
        public int shrinkInterval = 5;
        public int minRadius = 3;
    }

    /** One entry of the random item pool. */
    @SuppressWarnings("deprecation")
    @Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
    public static class ItemEntry extends OkaeriConfig {
        public String material = "STONE";
        public int amount = 1;
        public int weight = 1;
        public String name = "";
        public List<String> lore = new ArrayList<>();
        public Map<String, Integer> enchantments = new LinkedHashMap<>();
        public boolean unbreakable = false;
        public List<ItemEntry> extra = new ArrayList<>();

        static ItemEntry of(String material, int amount, int weight) {
            ItemEntry entry = new ItemEntry();
            entry.material = material;
            entry.amount = amount;
            entry.weight = weight;
            return entry;
        }

        ItemEntry named(String name) {
            this.name = name;
            return this;
        }

        ItemEntry enchant(String enchantment, int level) {
            enchantments.put(enchantment, level);
            return this;
        }

        static List<ItemEntry> defaults() {
            ItemEntry bow = of("BOW", 1, 5).named("<aqua>Punch Bow").enchant("punch", 1).enchant("infinity", 1);
            bow.unbreakable = true;
            bow.extra.add(of("ARROW", 1, 1));
            return new ArrayList<>(List.of(
                    of("STICK", 1, 12).named("<gold><bold>Knockback Stick").enchant("knockback", 2),
                    of("WHITE_WOOL", 16, 14),
                    of("OAK_PLANKS", 16, 8),
                    of("SNOWBALL", 8, 10),
                    of("EGG", 8, 6),
                    of("WIND_CHARGE", 3, 7),
                    of("ENDER_PEARL", 1, 5),
                    of("FISHING_ROD", 1, 6),
                    bow,
                    of("STONE_SWORD", 1, 5).enchant("knockback", 1),
                    of("TNT", 2, 5),
                    of("GOLDEN_APPLE", 1, 4),
                    of("COBWEB", 2, 4),
                    of("SLIME_BLOCK", 4, 4),
                    of("SHIELD", 1, 3)));
        }
    }
}
