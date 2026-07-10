# RegionMusic

Play music for players when they enter WorldGuard regions.

## Features

- Per-region music with custom resource-pack sounds or vanilla music discs
- Playlists with sequential or shuffle ordering, optional per-track volume
- Time-of-day and weather variants (night / rain / thunder)
- Admin `/test` command to force a variant for previewing
- Whole-world music via the special `__global__` region
- Per-player toggle and volume preferences (persisted)
- First-time region discovery messages (cached in-memory, persisted to player PDC)
- Optional vanilla biome music suppression
- Event-driven region detection (PlayerMoveEvent) — no polling overhead while stationary
- PlaceholderAPI integration for menus and status displays
- Brigadier commands with per-command permissions and tab-completion

## Requirements

- Paper 26.1.2 (or compatible)
- Java 25
- WorldGuard 7.0.10+ (hard dependency)
- PlaceholderAPI (optional)

## Installation

1. Drop the jar in your `plugins/` folder.
2. Restart the server. The default `config.yml` is written on first start.
3. Edit `plugins/RegionMusic/config.yml` to configure regions, then run `/regionmusic reload`.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/regionmusic help` | Show command help | `regionmusic.admin` (admin help) / `regionmusic.toggle` (player help) |
| `/regionmusic reload` | Reload config and messages from disk | `regionmusic.admin` |
| `/regionmusic toggle [on\|off]` | Toggle region music for yourself | `regionmusic.toggle` |
| `/regionmusic toggle <player> [on\|off]` | Toggle region music for another player | `regionmusic.admin` |
| `/regionmusic status` | Show your current music | (none) |
| `/regionmusic status <player>` | Show another player's music | `regionmusic.admin` |
| `/regionmusic volume` | Show your music volume | `regionmusic.volume` |
| `/regionmusic volume <0-100>` | Set your music volume | `regionmusic.volume` |
| `/regionmusic volume <player> <0-100>` | Set another player's music volume | `regionmusic.admin` |
| `/regionmusic preview <sound> [volume]` | Preview a sound (for testing resource packs) | `regionmusic.admin` |
| `/regionmusic preview stop` | Stop a running preview | `regionmusic.admin` |
| `/regionmusic test <night\|rain\|thunder\|clear>` | Force a variant for testing without waiting for real conditions | `regionmusic.admin` |
| `/regionmusic list` | List configured regions, with the current one highlighted | `regionmusic.admin` |

Alias: `/rmusic`.

## Permissions

| Permission | Description | Default |
|---|---|---|
| `regionmusic.*` | All RegionMusic permissions | `op` |
| `regionmusic.admin` | Admin commands (reload, toggle/volume/status for others, preview, list) | `op` |
| `regionmusic.toggle` | Toggle region music on/off for yourself | `true` |
| `regionmusic.volume` | View and set your personal music volume | `true` |

## Configuration

All gameplay settings live in `plugins/RegionMusic/config.yml`; player-facing messages live in `plugins/RegionMusic/messages.yml`.

Key knobs (see comments in `config.yml` for the full reference):

- `check-interval` — how often (in ticks) to re-check player positions
- `transition-delay` — pause between two music regions for a cleaner crossover
- `stop-vanilla-music` — suppress vanilla biome music; pair with `category: RECORD` on each region for full suppression
- `regions.<world>.<id>` — one entry per WorldGuard region (or `__global__` for the whole world)
  - `sound:` / `sounds:` (single track or playlist)
  - `volume`, `loop`, `duration`, `category`, `priority`, `order`
  - `variants.{night|rain|thunder}` for time/weather overrides

Player-specific state (toggle preference, custom volumes) is persisted to `plugins/RegionMusic/player-data.yml`. First-time region discoveries are stored per-player in the persistent data container (PDC).

## Placeholders

Available if PlaceholderAPI is installed:

| Placeholder | Returns |
|---|---|
| `%regionmusic_enabled%` | `true` if music is enabled for the player |
| `%regionmusic_toggled%` | `true` if music is toggled off (inverse of `enabled`) |
| `%regionmusic_playing%` | `true` if music is currently playing |
| `%regionmusic_sound%` | Current sound key |
| `%regionmusic_region%` | Current region's display name (falls back to region id) |
| `%regionmusic_region_id%` | Current region's raw id (always unformatted) |
| `%regionmusic_world%` | Current world name |
| `%regionmusic_volume%` | Effective volume, 0–100 |
| `%regionmusic_volume_decimal%` | Effective volume, 0.00–1.00 |
| `%regionmusic_volume_personal%` | Player's personal volume setting (0–100) |
| `%regionmusic_track%` | Current track display name |
| `%regionmusic_variant%` | Active variant (`night` / `rain` / `thunder`) or empty |
