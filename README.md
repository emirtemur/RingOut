# RingOut

[![Build](https://github.com/emirtemur/RingOut/actions/workflows/build.yml/badge.svg)](https://github.com/emirtemur/RingOut/actions/workflows/build.yml)

A Paper minigame plugin: **the last player standing inside the ring wins.**

A colorful ring split into slices floats over the void. Every player spawns on a slice, receives a random item every few seconds, and tries to knock everyone else off. Fall below the ring or stand outside it and you're out; the last one left wins.

## Features

- **Multiple arenas**: run as many rings as you like, each with its own game, stored in its own file.
- **Generated arenas**: a ring of colored concrete slices with an edge and a core, built in batches per tick so it doesn't lag, with the chunks loaded while it builds.
- **Void world**: `/ro createworld` creates an empty world so falling players really fall.
- **Random items**: a weighted, fully configurable item pool (knockback stick, wool, snowballs, wind charges, ender pearls, punch bow, auto-primed TNT and more).
- **Fair elimination**: players are out when they fall below the ring, or when they stand outside it (for example on a bridge) for longer than a short grace period. Being knocked into the air past the edge and landing back inside doesn't count.
- **Sudden death**: after a set time the ring shrinks one layer at a time.
- **Server-wide hub**: the whole server runs RingOut. Players join into a protected hub with an empty inventory and return there after every game.
- **Arena menu**: a compass in the hub opens a menu of all arenas with their live state and player count; click one to join. The menu is a DeluxeMenus-style file you can redesign.
- **Clean resets**: blocks players place are tracked and removed, and the ring is rebuilt after every game.
- **Boss bar, titles, sounds and fireworks**, with all text customizable using [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

## Requirements

- Paper 1.21.11
- Java 21

## Installation

1. Download `RingOut-<version>.jar` (or build it, see below) and put it in your server's `plugins` folder.
2. Start the server.
3. Create and build an arena from the console or in game:

```
/ro createworld
/ro create first ringout_world 0 150 0
/ro build first
```

Add more arenas the same way, at least 10 blocks apart. Then stand where players should wait between games and run `/ro sethub`. Players can now join with `/ro join <arena>`.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/ro join <arena>` | Join an arena | `ringout.play` |
| `/ro leave` | Leave your game | `ringout.play` |
| `/ro list` | List the arenas and their state | `ringout.play` |
| `/ro menu [menu]` | Open a menu (default: the arena menu) | `ringout.play` |
| `/ro create <arena> [world x y z]` | Create an arena (in game: centered on the block you stand on) | `ringout.admin` |
| `/ro delete <arena>` | Delete an arena's file (its blocks stay in the world) | `ringout.admin` |
| `/ro setcenter <arena> [world x y z]` | Move the ring center | `ringout.admin` |
| `/ro setlobby <arena> [world x y z]` | Set the waiting spot (defaults to the ring center) | `ringout.admin` |
| `/ro radius <arena> <3-100>` | Set the ring radius | `ringout.admin` |
| `/ro slices <arena> <2-16>` | Set the number of slices | `ringout.admin` |
| `/ro build <arena>` | Build the ring | `ringout.admin` |
| `/ro start <arena>` | Force start (even with one player) | `ringout.admin` |
| `/ro stop <arena>` | Stop the running game | `ringout.admin` |
| `/ro sethub [world x y z]` | Set the hub where players wait between games | `ringout.admin` |
| `/ro createworld [world]` | Create or load a void world (default `ringout_world`) | `ringout.admin` |
| `/ro reload` | Reload the configuration and all arenas | `ringout.admin` |

`/ringout` works as well as `/ro`. Arena setup commands only work while that arena has no game running; reload only works while every arena is idle.

Players with `ringout.bypass` (op by default) keep their inventory and game mode when they join the server and can build in the hub.

## Configuration

- `plugins/RingOut/config.yml`: the hub, defaults for new arenas, game rules (player counts, timers, PvP damage, whether explosions break the ring), sudden death, win commands, the item pool and all messages.
- `plugins/RingOut/arenas/<arena>.yml`: one file per arena with its location, size, colors and lobby.
- `plugins/RingOut/menus/<menu>.yml`: menus laid out like DeluxeMenus (title, size, items with material, slots, name, lore and click commands such as `[join]`, `[close]`, `[player]`, `[console]`, `[message]` and `[sound]`). `arenas.yml` is the menu the hub compass opens; its `arena_list` item repeats once per arena.

`config.yml` and the arena files are managed with [Okaeri Configs](https://github.com/OkaeriPoland/okaeri-configs): they are created with every setting and a comment on first start, and settings added in a newer version are filled in automatically. A file with a YAML error is never overwritten: config.yml keeps its last working version, and a broken arena file is skipped until it is fixed. Upgrading from a single-arena version moves the old arena to `arenas/default.yml`.

## Building

```bash
./gradlew build
```

The jar is written to `build/libs/`. To start a local test server with the plugin:

```bash
./gradlew runServer
```

## License

[MIT](LICENSE)
