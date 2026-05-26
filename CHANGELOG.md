# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- Music configured against the `__global__` region is now picked up. WorldGuard's `ApplicableRegionSet.iterator()` does not include the global region — it's used only for default-flag calculation — so the previous lookup loop never saw it. RegionMusic now queries the global region explicitly in addition to iterating applicable regions, letting admins configure music for an entire world via a single `__global__` entry.

### Changed

- Migrate to OakheartLib for config management (ConfigManager), messaging (MessageManager), command registration (CommandRegistrar), and debug logging (DebugLogger)
- Messages moved from config.yml to dedicated messages.yml with per-message display modes
- Prefix and error-prefix now baked into individual message templates instead of being prepended at runtime

## [1.0.0] - 2026-02-21

### Added

- Initial release
