# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Music configured against the `__global__` region is now picked up. WorldGuard's `ApplicableRegionSet.iterator()` does not include the global region — it's used only for default-flag calculation — so the previous lookup loop never saw it. RegionMusic now queries the global region explicitly in addition to iterating applicable regions, letting admins configure music for an entire world via a single `__global__` entry.

- Region music with `stop-vanilla-music: true` no longer silences itself. The listener used to call `stopSound(SoundStop.source(Sound.Source.MUSIC))` every ~2.5 seconds to suppress vanilla biome tracks, but that call also stopped region music (both share the same MUSIC source category), so admins heard ~2 seconds of music followed by silence on loop. The vanilla-music stop now runs in two phases: a single stop immediately before our own `playSound` in `MusicManager#playTrack` (kills any in-flight biome music as our track starts), and a periodic stop every ~2.5 seconds that fires ONLY when the active region track uses a non-MUSIC source. Setting `category: RECORD` (or any other source) on the region's config gives admins both full vanilla suppression AND no self-silencing; sticking with `category: MUSIC` falls back to the once-per-track stop and accepts possible vanilla overlap until the next loop boundary.

- Streamed music tracks now play reliably for the region/preview commands. The previous entity-attached playback (`Sound.Emitter.self()`) didn't always work for long streamed OGG tracks under the MUSIC source — the client treats music as a positional/global sound, not an entity sound. Playback now uses positional `playSound(sound, x, y, z)` at the player's location, matching what `/playsound` does.

### Changed

- Migrate to OakheartLib for config management (ConfigManager), messaging (MessageManager), command registration (CommandRegistrar), and debug logging (DebugLogger)
- Messages moved from config.yml to dedicated messages.yml with per-message display modes
- Prefix and error-prefix now baked into individual message templates instead of being prepended at runtime

## [1.0.0] - 2026-02-21

### Added

- Initial release
