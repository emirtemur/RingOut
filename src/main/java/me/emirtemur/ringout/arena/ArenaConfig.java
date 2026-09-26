package me.emirtemur.ringout.arena;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;
import eu.okaeri.configs.annotation.NameModifier;
import eu.okaeri.configs.annotation.NameStrategy;
import eu.okaeri.configs.annotation.Names;
import java.util.ArrayList;
import java.util.List;

/** plugins/RingOut/arenas/&lt;name&gt;.yml: one arena. Missing keys are filled in on load. */
@Header({
        "One RingOut arena. Set up with /ro commands; values can be edited here too.",
        "Run /ro reload after editing, and /ro build <arena> to rebuild the ring."
})
@SuppressWarnings("deprecation")
@Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
public class ArenaConfig extends OkaeriConfig {

    public String world = "ringout_world";
    public Center center = new Center();
    public int radius = 20;
    public int slices = 8;
    @Comment("Slice colors, used in order and repeated if there are more slices than colors.")
    public List<String> colors = new ArrayList<>(List.of("WHITE_CONCRETE"));
    public String edge = "WHITE_CONCRETE";
    public String core = "OBSIDIAN";
    public int coreRadius = 2;
    @Comment("Where players wait before the match; set with /ro setlobby. Unset = the ring center.")
    public Lobby lobby = new Lobby();
    @Comment("Managed by the plugin, do not edit.")
    public boolean built = false;
    public int builtRadius = 0;

    @SuppressWarnings("deprecation")
    @Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
    public static class Center extends OkaeriConfig {
        public int x = 0;
        public int y = 150;
        public int z = 0;
    }

    @SuppressWarnings("deprecation")
    @Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
    public static class Lobby extends OkaeriConfig {
        public boolean set = false;
        public String world = "";
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;
    }
}
