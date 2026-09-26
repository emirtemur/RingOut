package me.emirtemur.ringout.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Header;
import eu.okaeri.configs.annotation.NameModifier;
import eu.okaeri.configs.annotation.NameStrategy;
import eu.okaeri.configs.annotation.Names;

/**
 * plugins/RingOut/messages.yml: every text players see, in MiniMessage. Placeholders in angle
 * brackets (&lt;player&gt;, &lt;arena&gt;...) are filled in by the plugin. Looked up by key, see
 * Settings#message. Missing keys are filled in with these defaults on load.
 */
@Header({
        "RingOut messages. Every text uses MiniMessage: https://docs.advntr.dev/minimessage/format.html",
        "Words in angle brackets like <player>, <arena> or <count> are filled in by the plugin.",
        "Delete a line to get its default text back on the next /ro reload."
})
@SuppressWarnings("deprecation")
@Names(strategy = NameStrategy.HYPHEN_CASE, modifier = NameModifier.TO_LOWER_CASE)
public class Messages extends OkaeriConfig {

    public String prefix = "<dark_gray>[<gradient:#ff5555:#ffaa00>RingOut</gradient>]</dark_gray> ";
    public String noPermission = "<red>You don't have permission to do that.";
    public String playersOnly = "<red>Only players can use this command.";
    public String usage = "<gray>Usage: <yellow>/ro <join|leave|list|menu|create|delete|setcenter|setlobby|radius|slices|build|start|stop|sethub|createworld|reload>";
    public String arenaNotFound = "<red>There is no arena called <white><arena></white>. See <yellow>/ro list</yellow>.";
    public String arenaNotReady = "<red>This arena is not built yet. An admin must run <yellow>/ro build <arena></yellow>.";
    public String arenaResetting = "<yellow>The arena is being reset, try again in a moment.";
    public String alreadyInGame = "<red>You are already in a game.";
    public String notInGame = "<red>You are not in a game.";
    public String gameRunning = "<red>A game is already in progress there. Try again when it ends.";
    public String gameFull = "<red>The game is full.";
    public String joined = "<green><player> joined the ring! <gray>(<count>/<max>)";
    public String bypassInventoryCleared = "<yellow>Your inventory was cleared for the game. The bypass permission only keeps it when you join the server.";
    public String left = "<yellow><player> left the game. <gray>(<count>/<max>)";
    public String waiting = "<gray>Waiting for players... <yellow><count>/<min>";
    public String lobbyCountdown = "<yellow>The game starts in <gold><seconds></gold>s!";
    public String lobbyCancelled = "<red>Not enough players, the start was cancelled.";
    public String preparing = "<yellow>Preparing the arena...";
    public String countdownTitle = "<gold><seconds>";
    public String countdownSubtitle = "<yellow>Get ready!";
    public String startTitle = "<green><bold>FIGHT!";
    public String startSubtitle = "<gray>Knock everyone out of the ring";
    public String itemReceived = "<gray>You received <item><gray>.";
    public String eliminatedFell = "<red><player> fell out of the ring! <gray><alive> left.";
    public String eliminatedOutside = "<red><player> stepped out of the ring! <gray><alive> left.";
    public String eliminatedKilled = "<red><player> was knocked out! <gray><alive> left.";
    public String eliminatedLeft = "<red><player> left and was eliminated. <gray><alive> left.";
    public String eliminatedTitle = "<red><bold>ELIMINATED";
    public String eliminatedSubtitle = "<gray>You are now spectating.";
    public String suddenDeath = "<dark_red><bold>SUDDEN DEATH!</bold> <red>The ring is shrinking!";
    public String bossbar = "<yellow>Players left: <white><alive></white> <dark_gray>•</dark_gray> Next item: <white><seconds>s";
    public String bossbarSuddenDeath = "<red>Players left: <white><alive></white> <dark_gray>•</dark_gray> Ring radius: <white><radius>";
    public String winBroadcast = "<gold><bold><player></bold> <yellow>is the last one standing and wins!";
    public String winTitle = "<gold><bold><player> WINS!";
    public String winSubtitle = "<yellow>Last one in the ring";
    public String noWinner = "<gray>Nobody survived. No winner this time.";
    public String gameStopped = "<red>The game was stopped by an admin.";
    public String noGame = "<red>There is no game to stop in <white><arena></white>.";
    public String startNotEnough = "<red>Need at least <min> player(s) to start.";
    public String started = "<green>Starting the game in <white><arena></white>.";
    public String stopped = "<green>Game in <white><arena></white> stopped.";
    public String menuItemName = "<gold><bold>Arenas</bold> <gray>(right-click)";
    public String menuItemLore = "<gray>Pick an arena to join";
    public String menuNotFound = "<red>There is no menu called <white><menu></white>. Check plugins/RingOut/menus.";
    public String arenaListHeader = "<gold>Arenas:";
    public String arenaListEntry = "<dark_gray>- <white><arena></white> <state> <gray><count>/<max> <dark_gray>(<world> <x> <y> <z>, radius <radius>)";
    public String arenaListEmpty = "<gray>No arenas yet. An admin can create one with <yellow>/ro create <name></yellow>.";
    public String stateNotBuilt = "<dark_gray>not built";
    public String stateWaiting = "<green>waiting";
    public String stateStarting = "<yellow>starting soon";
    public String stateBuilding = "<yellow>resetting";
    public String statePlaying = "<red>in game";
    public String stateEnding = "<red>ending";
    public String invalidArenaName = "<red>Arena names may only use a-z, 0-9, _ and - (up to 32 characters).";
    public String arenaExists = "<red>An arena called <white><arena></white> already exists.";
    public String arenaFileExists = "<red>arenas/<arena>.yml already exists but is not loaded (it has an error). Fix or remove the file and run <yellow>/ro reload</yellow>.";
    public String arenaCreated = "<green>Arena <white><arena></white> created at <white><world> <x> <y> <z></white>. Run <yellow>/ro build <arena></yellow> to build it.";
    public String arenaCreateFailed = "<red>Could not save arenas/<arena>.yml, so the arena was not created. Check the console.";
    public String arenaDeleted = "<green>Arena <white><arena></white> deleted. Its blocks are still in the world.";
    public String arenaDeleteFailed = "<red>Could not delete arenas/<arena>.yml. Check the console.";
    public String arenaOverlap = "<red>That ring would come too close to arena <white><other></white>. Keep at least <gap> blocks between rings.";
    public String arenaBuilding = "<yellow>Building <white><arena></white>...";
    public String arenaBuilt = "<green>Arena <white><arena></white> built <gray>(radius <radius>, <slices> slices).";
    public String arenaBuildFailed = "<red>Building <white><arena></white> failed: <error>";
    public String arenaBusy = "<red><white><arena></white> is being built, try again in a moment.";
    public String arenaNotSaved = "<red>arenas/<arena>.yml has an error, so this change is applied but not saved yet. Fix the file: it is saved with the next build, or set it again after <yellow>/ro reload</yellow>.";
    public String centerSet = "<green>Center of <white><arena></white> set to <white><world> <x> <y> <z></white>. Run <yellow>/ro build <arena></yellow> to build it.";
    public String lobbySet = "<green>Lobby of <white><arena></white> set.";
    public String radiusSet = "<green>Radius of <white><arena></white> set to <white><radius></white>. Run <yellow>/ro build <arena></yellow> to apply.";
    public String slicesSet = "<green>Slices of <white><arena></white> set to <white><slices></white>. Run <yellow>/ro build <arena></yellow> to apply.";
    public String hubSet = "<green>Hub set. Players are sent here when they join the server or a game ends.";
    public String hubNotSaved = "<red>config.yml has an error, so the hub is used for now but not saved. Fix the file, run <yellow>/ro reload</yellow> and set it again.";
    public String configLoadFailed = "<red>config.yml failed to load, so the hub can't be changed. Fix the file and run <yellow>/ro reload</yellow> first.";
    public String worldCreated = "<green>Void world <white><world></white> is ready.";
    public String worldNotFound = "<red>World <white><world></white> is not loaded.";
    public String worldNotVoid = "<red>World <white><world></white> already exists and is not a void world, so RingOut leaves it alone.";
    public String invalidNumber = "<red><value> is not a valid number.";
    public String outOfRange = "<red>Value must be between <min> and <max>.";
    public String cannotWhileRunning = "<red>You can't do that while a game is running there.";
    public String reloadBusy = "<red>Reload only works while nobody is in a game and no arena is being built.";
    public String reloaded = "<green>Configuration and <count> arena(s) reloaded.";
    public String reloadFailed = "<red>config.yml or messages.yml has an error, so its previous version is kept. <count> arena(s) reloaded. Check the console.";
}
