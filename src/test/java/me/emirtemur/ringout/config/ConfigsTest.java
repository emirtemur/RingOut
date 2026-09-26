package me.emirtemur.ringout.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.okaeri.configs.exception.OkaeriException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.emirtemur.ringout.arena.ArenaConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Okaeri read/write of the plugin's config files, the same way the plugin does it. */
class ConfigsTest {

    @TempDir
    Path dir;

    private File file(String content) throws IOException {
        Path path = dir.resolve("config.yml");
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path.toFile();
    }

    private String read(File file) throws IOException {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void defaultsAreWrittenWithHyphenKeysAndReadBack() throws IOException {
        File file = dir.resolve("config.yml").toFile();
        Configs.write(Configs.create(PluginConfig.class), file);

        String yaml = read(file);
        assertTrue(yaml.contains("arena-defaults:"), yaml);
        assertTrue(yaml.contains("min-players: 2"), yaml);
        assertTrue(yaml.contains("outside-grace-ticks: 20"), yaml);
        assertFalse(yaml.contains("no-permission:"), "texts live in messages.yml now");

        PluginConfig config = Configs.read(PluginConfig.class, file);
        assertEquals(16, config.game.maxPlayers);
        assertEquals(15, config.items.size());
        assertEquals("arenas", config.hub.menu);
    }

    @Test
    void missingKeysAreFilledInAndKeptValuesStay() throws IOException {
        File file = file("game:\n  min-players: 5\n");
        PluginConfig config = Configs.read(PluginConfig.class, file);
        assertEquals(5, config.game.minPlayers);
        assertEquals(16, config.game.maxPlayers);

        Configs.write(config, file);
        String yaml = read(file);
        assertTrue(yaml.contains("min-players: 5"), yaml);
        assertTrue(yaml.contains("max-players: 16"), yaml);
    }

    @Test
    void completeFilesAreNotRewritten() throws IOException {
        File file = dir.resolve("config.yml").toFile();
        assertTrue(Configs.writeIfChanged(Configs.create(PluginConfig.class), file));
        long written = file.lastModified();

        PluginConfig config = Configs.read(PluginConfig.class, file);
        assertFalse(Configs.writeIfChanged(config, file));
        assertEquals(written, file.lastModified());

        config.game.minPlayers = 3;
        assertTrue(Configs.writeIfChanged(config, file));
    }

    @Test
    void brokenYamlThrowsAndIsNotReplacedByDefaults() throws IOException {
        File file = file("game:\n  min-players: [unclosed\n");
        assertThrows(OkaeriException.class, () -> Configs.read(PluginConfig.class, file));
        assertTrue(read(file).contains("[unclosed"));
    }

    @Test
    void messagesAreFoundByTheirFileKey() {
        Messages messages = Configs.create(Messages.class);
        assertEquals(messages.arenaNotFound, messages.get("arena-not-found"));
        assertEquals(messages.stateNotBuilt, messages.get("state-not-built"));
        assertEquals(messages.bossbarSuddenDeath, messages.get("bossbar-sudden-death"));
    }

    @Test
    void messagesMovedFromTheOldConfigKeepEditedAndUnknownTexts() throws IOException {
        // What the plugin does on the first start after messages left config.yml.
        Messages moved = Configs.create(Messages.class);
        moved.load(Map.of("prefix", "[Old] ", "joined", "WELCOME <player>!", "my-own-key", "x"));
        File file = dir.resolve("messages.yml").toFile();
        assertTrue(Configs.writeIfChanged(moved, file));

        String yaml = read(file);
        assertTrue(yaml.contains("RingOut messages."), "header comment");
        assertTrue(yaml.contains("my-own-key: x"), yaml);

        Messages read = Configs.read(Messages.class, file);
        assertEquals("[Old] ", read.prefix);
        assertEquals("WELCOME <player>!", read.joined);
        assertEquals(Configs.create(Messages.class).noPermission, read.noPermission);
        assertFalse(Configs.writeIfChanged(read, file));
    }

    @Test
    void loadErrorsKeepTheYamlPosition() throws IOException {
        File file = file("game:\n  min-players: [unclosed\n");
        OkaeriException error = assertThrows(OkaeriException.class, () -> Configs.read(PluginConfig.class, file));
        String described = Configs.describe(error);
        assertTrue(described.contains("line"), described);
    }

    @Test
    void settingsLookUpMessagesByKey() {
        Settings settings = new Settings(Configs.create(PluginConfig.class), Configs.create(Messages.class));
        assertEquals(Configs.create(Messages.class).menuNotFound, settings.raw("menu-not-found"));
        assertEquals("no-such-key", settings.raw("no-such-key"));
    }

    @Test
    void itemPoolDefaultsSurviveARoundTrip() throws IOException {
        File file = dir.resolve("config.yml").toFile();
        Configs.write(Configs.create(PluginConfig.class), file);
        PluginConfig config = Configs.read(PluginConfig.class, file);

        PluginConfig.ItemEntry bow = config.items.stream().filter(item -> item.material.equals("BOW")).findFirst().orElseThrow();
        assertTrue(bow.unbreakable);
        assertEquals(1, bow.enchantments.get("punch"));
        assertEquals("ARROW", bow.extra.getFirst().material);
    }

    @Test
    void configFromTheBukkitYamlVersionStillLoads() throws IOException {
        File file = file("""
                arena:
                  world: ringout_world
                  radius: 12
                hub:
                  menu-slot: 3
                  set: true
                  world: world
                  x: 1.5
                game:
                  item-interval: 9
                items:
                  - material: STICK
                    name: "<gold>Stick"
                    weight: 12
                    enchantments:
                      knockback: 2
                  - material: WHITE_WOOL
                    amount: 16
                    weight: 14
                messages:
                  prefix: "[Old] "
                """);
        PluginConfig config = Configs.read(PluginConfig.class, file);
        assertEquals(3, config.hub.menuSlot);
        assertTrue(config.hub.set);
        assertEquals(1.5, config.hub.x);
        assertEquals(9, config.game.itemInterval);
        assertEquals(2, config.items.size());
        assertEquals(2, config.items.getFirst().enchantments.get("knockback"));
        assertEquals(16, config.items.get(1).amount);

        // The old arena section is not part of the config anymore, but it is kept for the move.
        Configs.write(config, file);
        String yaml = read(file);
        assertTrue(yaml.contains("arena:"), yaml);
        assertTrue(yaml.contains("radius: 12"), yaml);
    }

    @Test
    void arenaFilesRoundTrip() throws IOException {
        File file = dir.resolve("a.yml").toFile();
        ArenaConfig written = Configs.create(ArenaConfig.class);
        written.center.x = 60;
        written.radius = 25;
        written.built = true;
        written.builtRadius = 25;
        written.lobby.set = true;
        written.lobby.world = "ringout_world";
        Configs.write(written, file);

        String yaml = read(file);
        assertTrue(yaml.contains("built-radius: 25"), yaml);
        assertTrue(yaml.contains("core-radius:"), yaml);

        ArenaConfig read = Configs.read(ArenaConfig.class, file);
        assertEquals(60, read.center.x);
        assertEquals(25, read.radius);
        assertTrue(read.built);
        assertTrue(read.lobby.set);
        assertFalse(read.colors.isEmpty());
    }
}
