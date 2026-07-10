# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.1] - 2026-05-27

### Fixed

- Discovery-cache load no longer crashes for players upgrading from pre-1.1 RegionMusic. Paper's PDC throws `IllegalArgumentException` when `get(key, type)` is called against a stored tag of a different type, rather than returning null. The legacy-to-LIST migration relied on the latter and was therefore unreachable for any player with the old comma-delimited STRING tag — the read blew up before the migration code ran. `readFromPdc` now probes with `has(key, type)` before each `get`, so the STRING-to-LIST migration actually runs on first access and the player's discovery state transparently upgrades to the modern format.

## [1.2.0] - 2026-05-27

### Added

- **Region `display-name` field.** Each region can now declare a player-facing name (`display-name: "The Spawn"`), falling back to the region ID if absent. MiniMessage formatting is supported, so admins can style the name (`display-name: "<#FCD472>The Spawn"`). Particularly useful with `__global__` regions, where the raw ID would otherwise leak into chat. The display name is used by `now-playing`, `music-stopped`, `region-discovered`, `status`, and `list`. New `<region_id>` placeholder exposes the raw ID for admins who still want it in their messages.
- **`%regionmusic_region_id%` PlaceholderAPI placeholder.** `%regionmusic_region%` now returns the display name; `%regionmusic_region_id%` returns the raw region ID for scripting.
- **PlayerMoveEvent-based region detection.** Region transitions now fire on movement (block-changed guard) instead of waiting for the next periodic tick. Sub-tick latency on actual transitions, near-zero cost for stationary players. `check-interval` is repurposed for variant re-evaluation only (weather/time-of-day for players standing inside variant regions).
- **Per-track `volume:` override.** Each entry in a playlist (or the single-sound region itself, via `track-volume:`) can specify a volume that replaces the region's base volume for just that track. The player's personal volume always scales on top.
- **`/regionmusic test <night|rain|thunder|clear>` admin command.** Forces a variant for the calling player so admins can preview variant setups without waiting for the matching weather or time of day. Override is in-memory only and clears on quit or `/regionmusic test clear`.
- **Tab-completion for `/regionmusic preview <sound>`.** Suggests the union of all configured region sounds (deduped) and the vanilla `minecraft:music_disc.*` keys.
- **Stop-vanilla-music sanity warning.** Config validation now emits a warning when `stop-vanilla-music: true` is paired with a region using `category: MUSIC`, explaining that vanilla music will only be suppressed at track boundaries and recommending `category: RECORD` for continuous suppression.

### Changed

- Discovered regions are now cached in memory per player. Lookup on region entry is O(1) against a `HashSet` instead of an O(n) scan of the PDC list. The PDC remains the source of truth — new discoveries write through. Cache loads lazily on first access and on player join, and unloads on quit. Players who were already online when the plugin loaded are also preloaded.
- Renamed `RegionTrack#volume` parsing path: single-sound regions accept an optional `track-volume:` at the region level; sound-list entries accept the more natural `volume:` per entry.

## [1.1.0] - 2026-05-27

### Added

- Playlist track advances now send a `now-playing` message for each new track, not just the first. The message can still be disabled by setting `now-playing.text` to an empty string in `messages.yml`.
- `README.md` documenting features, commands, permissions, configuration, and placeholders.

### Changed

- Migrated to OakheartLib for config management (ConfigManager), messaging (MessageManager), command registration (CommandRegistrar), and debug logging (DebugLogger).
- Messages moved from `config.yml` to a dedicated `messages.yml` with per-message display modes.
- Prefix and error-prefix are now baked into individual message templates instead of being prepended at runtime.
- Migrated `player-data.yml` from Bukkit's `YamlConfiguration` to OakheartLib's `ConfigManager`, with dirty-flag batched async flushes (60s interval) instead of an async write per mutation. On shutdown the pending flush runs synchronously.
- Migrated region-discovery storage in player PDC from a comma-delimited `STRING` to `PersistentDataType.LIST.strings()`. Legacy entries are auto-converted to the new format the first time a player is read.
- Reorganized packages: `MusicManager` and `PlayerDataManager` now live under `managers/`, `RegionConfig` and `RegionTrack` under `model/`, and `RegionMusicPlaceholders` under `placeholders/`. A new `model/RegionKey` record replaces the previous `world:regionId:variant` string parsing in the region listener.
- `config/ConfigManager#validate()` now returns `false` for fatal errors (e.g. no parseable regions at all), so `/regionmusic reload` can actually reject a broken config and keep the previous one. Per-region structural problems are reported as warnings, and the affected region is skipped at parse time.
- `config/ConfigManager#getRegionData()` now returns a deeply-immutable view (`Map.copyOf` on both the outer and inner maps).
- `/regionmusic help` now sends a single joined chat message instead of 12 separate messages. Each line is still customizable as its own `help-*` key in `messages.yml`.
- `MusicManager#stopMusic` now returns `boolean` to match `stopPreview` (true if a track was actually stopped).
- Switched `ConcurrentHashMap` to `HashMap` for the music-state and listener maps that only see main-thread access. Concurrent maps remain in `PlayerDataManager` where the flush task reads them from another thread.
- Dropped the redundant `forceCheck` flag in `RegionMusicListener`. Region-affecting events (join, teleport, respawn, world change) now schedule a single forced re-check directly, eliminating a dual-mechanism that occasionally raced with the periodic check.

### Fixed

- Music configured against the `__global__` region is now picked up. WorldGuard's `ApplicableRegionSet.iterator()` does not include the global region — it's used only for default-flag calculation — so the previous lookup loop never saw it. RegionMusic now queries the global region explicitly in addition to iterating applicable regions, letting admins configure music for an entire world via a single `__global__` entry.
- Region music with `stop-vanilla-music: true` no longer silences itself. The listener used to call `stopSound(SoundStop.source(Sound.Source.MUSIC))` every ~2.5 seconds to suppress vanilla biome tracks, but that call also stopped region music (both share the same MUSIC source category), so admins heard ~2 seconds of music followed by silence on loop. The vanilla-music stop now runs in two phases: a single stop immediately before our own `playSound` in `MusicManager#playTrack` (kills any in-flight biome music as our track starts), and a periodic stop every ~2.5 seconds that fires ONLY when the active region track uses a non-MUSIC source. Setting `category: RECORD` (or any other source) on the region's config gives admins both full vanilla suppression AND no self-silencing; sticking with `category: MUSIC` falls back to the once-per-track stop and accepts possible vanilla overlap until the next loop boundary.
- Streamed music tracks now play reliably for the region/preview commands. The previous entity-attached playback (`Sound.Emitter.self()`) didn't always work for long streamed OGG tracks under the MUSIC source — the client treats music as a positional/global sound, not an entity sound. Playback now uses positional `playSound(sound, x, y, z)` at the player's location, matching what `/playsound` does.
- `music-stopped` no longer leaks to chat as "Music stopped." on every region exit. The previous default used `display: none`, which OakheartLib's MessageManager treats as the default (chat) delivery mode. The default is now `text: ""` (truly silent); admins who want the message can set their own text and pick a real display mode.
- Removed dead `messages.yml` keys `no-permission` and `unknown-command` — Brigadier already handles these natively and the keys were never read.
- Clarified the `display:` option set in the `messages.yml` header to `chat`, `action_bar`, and `title`, and documented that empty text is the way to disable a message.

## [1.0.0] - 2026-02-21

### Added

- Initial release
