# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Fixed

- The `config.yml` comment and the console line at start now say that each `/fmap` a player runs sends one event naming the subcommand, as the README and `CONFIG.md` already did.
- The `/fmap` usage string now lists `/fmap stats json`.

## [0.2.2] - 2026-11-06

### Fixed

- Usage reporting no longer sends text that is not a subcommand: `/fmap <anything else>` is not reported.
- `stats.json` is now replaced atomically, so a script reading it never sees an empty or partial file.

## [0.2.1] - 2026-10-30

### Changed

- Usage reporting now counts a `command` event only when a player runs the command; console and command-block commands are no longer reported.

## [0.2.0] - 2026-10-29

### Changed

- The vendored trace client is now 0.7.0, which coalesces identical usage reports sent within 60 seconds into one event carrying their count.

## [0.1.0] - 2026-10-25

### Added

- First milestone (M1) of RFC 0002, a faction-native map for Medieval Factions:
  - direct surface capture of loaded chunks on the main thread within a per-tick budget, with debounced block-change detection and a slow sweep;
  - greedy meshing and 8 × 8-chunk tiles on one background thread, with a persisted palette and a per-tile column cache so tiles survive restarts byte for byte;
  - an embedded static web server (localhost by default) and a three.js viewer with territory tint, claim borders, faction names (on by default), ally, war and vassal relations, and faction homes (off by default, `factions.showHomes`);
  - Medieval Factions read through its service getters, re-read on its events and on a 30-second poll for changes that fire no event;
  - `/fmap status|stats|reset|recapture`;
  - usage reporting through the fleet's trace client, off until a key is added;
  - CI: unit tests, a bytecode read-only check, the shared Bukkit API compatibility check for 1.21.11, 26.2 and 26.3, and a bot-driven smoke and performance gate on Spigot 26.3.
  - release workflows: `dev-release.yml` republishes the rolling `dev` prerelease on every merge to `main`, and `release.yml` attaches a jar to a published release unless one is already attached.
