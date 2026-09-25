# RingOut

A Paper minigame plugin: **the last player standing inside the ring wins.**

A colorful ring split into slices floats over the void. Every player spawns on a slice, receives a random item every few seconds, and tries to knock everyone else off. Fall below the ring or stand outside it and you're out; the last one left wins.

## Features

- **Generated arena**: a ring of colored concrete slices with an edge and a core, built in batches per tick so it doesn't lag, with the chunks loaded while it builds.
- **Void world**: `/ro createworld` creates an empty world so falling players really fall.
- **Random items**: a weighted, fully configurable item pool (knockback stick, wool, snowballs, wind charges, ender pearls, punch bow, auto-primed TNT and more).
- **Fair elimination**: players are out when they fall below the ring, or when they stand outside it (for example on a bridge) for longer than a short grace period. Being knocked into the air past the edge and landing back inside doesn't count.
- **Sudden death**: after a set time the ring shrinks one layer at a time.
- **Safe inventories**: inventories, levels and effects are saved to disk before a game and restored afterwards, even after a crash or restart.
- **Clean resets**: blocks players place are tracked and removed, and the ring is rebuilt after every game.
- **Boss bar, titles, sounds and fireworks**, with all text customizable using [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

## Requirements

- Paper 1.21.11
- Java 21

## Installation

1. Download `RingOut-<version>.jar` (or build it, see below) and put it in your server's `plugins` folder.
2. Start the server.
3. Set up the arena from the console or in game:

```
/ro createworld
/ro setcenter ringout_world 0 150 0
/ro build
```

Players can now join with `/ro join`.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/ro join` | Join the game | `ringout.play` |
| `/ro leave` | Leave the game | `ringout.play` |
| `/ro start` | Force start (even with one player) | `ringout.admin` |
| `/ro stop` | Stop the running game | `ringout.admin` |
| `/ro setcenter [world x y z]` | Set the ring center (in game: the block you stand on) | `ringout.admin` |
| `/ro setlobby [world x y z]` | Set the lobby (defaults to the ring center) | `ringout.admin` |
| `/ro radius <3-100>` | Set the ring radius | `ringout.admin` |
| `/ro slices <2-16>` | Set the number of slices | `ringout.admin` |
| `/ro build` | Build the ring | `ringout.admin` |
| `/ro createworld` | Create or load the void arena world | `ringout.admin` |
| `/ro reload` | Reload the configuration | `ringout.admin` |

`/ringout` works as well as `/ro`. Setup commands only work while no game is running.

## Configuration

Everything lives in `plugins/RingOut/config.yml`: the arena (colors, radius, slices), game rules (player counts, timers, PvP damage, whether explosions break the ring), sudden death, win commands, the item pool and all messages.

If `config.yml` has a YAML error, the plugin keeps the last working configuration and never overwrites your file.

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
