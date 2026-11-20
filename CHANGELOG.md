# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

## [0.3.0] - 2026-11-20

### Fixed

- Usage reporting stayed on when the opt-out was written in a way the plugin did not recognise: under another spelling of the block (`usage_reporting:`, `usageReporting:`), as `usage-reporting: false`, or with another spelling of the switch (`enable: false`, `disabled: true`). When the plugin wrote `config.yml` (on a version change, for example), it also added the bundled `usage-reporting` block, `enabled: true`, next to the operator's setting. Each of these now counts as off, and every save writes `usage-reporting.enabled: false` into the real block, so the file shows that reporting is off.
- Usage reporting can no longer be turned back on by the plugin once it has been turned off. A `config.yml` with a YAML error in it is loaded by Bukkit as empty, so the plugin saw no `usage-reporting` block and wrote the bundled one (with `enabled: true`) over the whole file on startup, opt-out included. Such a file is now never overwritten: the console says so and reporting stays off until the error is fixed. Every save of `config.yml` also checks the file on disk first and keeps an opt-out written there while the server was running. A quoted value such as `enabled: "false"` was read as on; now only `true`, `on` or `yes` turns reporting on, and any other value turns it off.
- An empty `usage-reporting.enabled:` (no value) now turns usage reporting off. Bukkit drops a key with no value, so the bundled `true` used to win.
- The vendored trace client is now 0.8.0, in which every opt-out fails closed: in `plugins/trace/config.yml` a quoted `enabled: "false"`, an empty `enabled:`, a typo, or a later `enabled: false` under an earlier `enabled: true` now turn reporting off, as does a file that is there but cannot be read; `TRACE_USAGE_REPORTING` set to anything but blank or `true`/`on`/`yes`/`1`, `DO_NOT_TRACK` set to anything but blank or `0`/`false`/`off`/`no`, and an environment that cannot be read now turn it off too. Details: https://github.com/Stephenson-Software/trace-client-java/pull/51
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
