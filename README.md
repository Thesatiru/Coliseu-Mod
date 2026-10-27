# Colosseum for Cobblemon

A **server-side** Fabric mod that runs automatic, recurring Cobblemon PvP tournaments.
Every hour (configurable) a queue opens in chat, players click **[JOIN]**, and a single-elimination
bracket plays out by itself across as many arenas as you set up. The winners get rewards from a
weighted loot pool.

> **Status: beta.** Built and tested on Minecraft 1.21.1, Fabric and Cobblemon 1.7.3 only.
> Players do **not** need to install anything.

## Features

- **Automatic events**: a queue opens on a timer, with chat announcements, a countdown and a clickable join button.
- **Random rule each tournament**: legendary limit drawn from a configurable list (for example anything goes / max 1 / max 3), plus an optional maximum level.
- **Players fight with their own teams and levels.** Teams are healed before every fight and when a player leaves the event.
- **Single-elimination bracket** with byes for odd player counts, forfeits on disconnect or fleeing, and a time limit per fight.
- **Several arenas at once**: add as many arenas as you want; fights of a round run in parallel.
- **Safe return**: each player's original position is saved to disk and restored after the event, even after a crash or a disconnect.
- **Loot pool**: weighted rewards per placement, run as server commands, with chat announcements and delivery on next login for offline winners.
- **Random item rewards** by ID filter or regex (for example "a random TM" without listing every TM).
- **14 languages built in**: English, Portuguese (Brazil), Spanish, French, German, Italian, Polish, Russian, Ukrainian, Turkish, Chinese (Simplified and Traditional), Japanese and Korean. Add or tweak any language with a JSON file.
- **Permissions**: LuckPerms-compatible nodes (optional).
- **Live reload**: change config, loot and language without restarting.

## Requirements

- Minecraft 1.21.1
- Fabric Loader and Fabric API
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
- [Cobblemon](https://cobblemon.com) 1.7.3

## Installation

1. Put the mod jar in the server's `mods` folder, next to Cobblemon.
2. Start the server once. Config files are created in `config/`.
3. Set up the lobby and at least one arena (below).

## Quick setup

Stand where you want players to wait, then run (as an operator):

```
/colosseum admin setlobby
```

Then, for each arena, stand where fighter 1 should be and run:

```
/colosseum admin arena 1 pos1
```

stand where fighter 2 should be and run:

```
/colosseum admin arena 1 pos2
```

Repeat with `arena 2`, `arena 3`, and so on for more arenas. Check everything with:

```
/colosseum admin arenas
```

To test right away, run `/colosseum admin open`. A tournament needs at least 2 players, so ask a
friend (or use a second account) to join.

## Commands

Every command also exists in Portuguese (`/coliseu entrar`, `/coliseu admin abrir`, ...).

| Command | Who | What it does |
|---|---|---|
| `/colosseum join` | players | Join the open queue |
| `/colosseum leave` | players | Leave the queue, or forfeit during a tournament |
| `/colosseum status` | players | Queue / tournament status |
| `/colosseum help` | everyone | List commands |
| `/colosseum admin open` | admin | Open a queue now |
| `/colosseum admin cancel` | admin | Cancel the open queue |
| `/colosseum admin end` | admin | End the running tournament |
| `/colosseum admin setlobby` | admin | Set the waiting area |
| `/colosseum admin arena <n> pos1\|pos2` | admin | Set a fighter position of arena n |
| `/colosseum admin arenas` | admin | Show the arena setup |
| `/colosseum admin reload` | admin | Reload config, loot and language |
| `/colosseum admin rewards` | admin | List rewards, chances and their status |
| `/colosseum admin reward <player> [n]` | admin | Roll (or force reward n) for a player, for testing |
| `/colosseum admin rewardlist <n>` | admin | Show which items a random-item reward can give |
| `/colosseum admin itemid` | admin | Show the ID of the item in your hand |
| `/colosseum admin finditems <text>` | admin | Search item IDs |

## Permissions

If [fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) is present (LuckPerms provides it),
these nodes are used. Without it, operators (level 2) are admins and everyone can play.

| Node | Default | For |
|---|---|---|
| `coliseu.admin` | OP level 2 | all `admin` commands |
| `coliseu.play` | everyone | join, leave, status |

## Configuration

`config/colosseum.json` (created on first start; use `/colosseum admin reload` after editing):

| Key | Default | Meaning |
|---|---|---|
| `language` | `en_us` | Language code (see [Languages](#languages)) |
| `intervalMinutes` | 60 | Minutes between automatic queues |
| `queueSeconds` | 60 | How long the queue stays open |
| `minPlayers` | 2 | Minimum players to start (at least 2) |
| `maxPlayers` | 8 | Maximum players in the queue |
| `announceAtSeconds` | `[60, 30, 10]` | When to repeat the countdown in chat |
| `stallTimeoutSeconds` | 600 | The tournament ends if nothing progresses for this long |
| `battleTimeoutSeconds` | 480 | Maximum length of one fight; after that the winner is picked at random |
| `pauseBetweenFightsSeconds` | 5 | Pause before the next fight starts |
| `fightCountdownSeconds` | 0 | Seconds between teleporting and starting a fight (0 = instant) |
| `maxLevel` | 100 | Teams with a Pokémon above this level are rejected (100 = no limit) |
| `legendaryLimitOptions` | `[-1, 1, 3]` | Legendary rules to draw from: -1 = unlimited, 0 = none, n = at most n |
| `legendaryLabels` | legendary, mythical, ultra_beast, paradox | Cobblemon labels that count as "legendary" |
| `lobby`, `arenas` | set in game | Positions (use the admin commands) |

> **Upgrading from 0.1.0:** the old files (`coliseu.json`, `coliseu_loot.json`, `coliseu_lang/`...) are renamed and converted to the new English keys automatically on the first start. Nothing is lost.

### Loot pool

`config/colosseum_loot.json`:

```json
{
  "championRolls": 1,
  "runnerUpRolls": 1,
  "thirdPlaceRolls": 1,
  "participationRolls": 0,
  "rewards": [
    {
      "name": "15x Rare Candy",
      "weight": 50,
      "options": [ { "commands": ["give {player} cobblemon:rare_candy 15"] } ]
    },
    {
      "name": "Random TM",
      "weight": 25,
      "randomItem": { "include": ["some_mod:tm_"], "exclude": [], "amount": 1 }
    }
  ]
}
```

- `*Rolls`: how many rolls each placement gets (0 = nothing).
- `weight`: weight in the draw. If all weights add up to 100, the weight is the percentage.
- `options`: one is picked at random; each runs its `commands` as the console. `{player}` is the winner's name.
- `randomItem`: picks a random item whose ID contains all `include` texts, contains none of `exclude`, and matches `regex` (optional).
- `highlight`: `true` shows the announcement in bold gold with stars.
- `note`: a reminder for yourself; the mod ignores it.
- A reward with no way to be delivered is left out of the draw and admins are warned (see `/colosseum admin rewards`).

### Languages

Set `"language"` in `config/colosseum.json` to one of the built-in codes:

| Code | Language | Code | Language |
|---|---|---|---|
| `en_us` | English | `ru_ru` | Русский |
| `pt_br` | Português (Brasil) | `uk_ua` | Українська |
| `es_es` | Español | `tr_tr` | Türkçe |
| `fr_fr` | Français | `zh_cn` | 简体中文 |
| `de_de` | Deutsch | `zh_tw` | 繁體中文 |
| `it_it` | Italiano | `ja_jp` | 日本語 |
| `pl_pl` | Polski | `ko_kr` | 한국어 |

Regional variants fall back to the closest file: `es_mx`, `es_ar` → `es_es`, `pt_pt` → `pt_br`, `fr_ca` → `fr_fr`, `zh_hk` → `zh_tw`, and so on.
Commands are always `/colosseum ...` (plus the Portuguese `/coliseu ...` aliases).

To add a language or change some phrases, create `config/colosseum_lang/<code>.json` with the keys you want
(copy them from `coliseumod_lang/en_us.json` inside the jar). Missing keys fall back to the built-in text, then to English.
Translations were machine-assisted; corrections from native speakers are very welcome via GitHub issues or pull requests.

## How a tournament works

1. A random legendary rule is drawn and announced, then the queue opens.
2. When it closes, teams are checked again. With enough players, everyone is healed and teleported to the lobby (their original position is saved).
3. Each round, players are paired at random; with an odd number, one gets a bye. Fights run in the free arenas, with the players' real teams.
4. The winner of a fight returns to the lobby; the loser is healed and goes back where they came from.
5. The last player standing is the champion. The podium is announced and rewards are delivered.

Disconnecting, fleeing or using `/colosseum leave` during a fight counts as a loss.

## Known limitations

- Only tested on Minecraft 1.21.1 with Cobblemon 1.7.3 on Fabric. Cobblemon's API changes between versions, so other versions may need changes.
- Fights are 1v1 singles. There is no spectator mode yet.
- Fights use the players' real parties, so Pokémon take damage during the event (they are healed before every fight and when a player leaves). Whether battles grant experience or EVs is up to Cobblemon's own rules.

## Building

The project uses Fabric Loom, which currently needs **JDK 25 to run Gradle**. The mod itself targets Java 21.

1. Download the Cobblemon jar for your version and save it as `libs/cobblemon.jar` (it is not included in this repository).
2. Run `./gradlew build` with JDK 25.
3. The jar is created in `build/libs/` (use the one without `-sources`).

## License

See [LICENSE](LICENSE).
