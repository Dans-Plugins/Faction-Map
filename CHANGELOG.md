# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- First milestone (M1) of RFC 0002, a faction-native map for Medieval Factions:
  - direct surface capture of loaded chunks on the main thread within a per-tick budget, with debounced block-change detection and a slow sweep;
  - greedy meshing and 8 × 8-chunk tiles on one background thread, with a persisted palette and a per-tile column cache so tiles survive restarts byte for byte;
  - an embedded static web server (localhost by default) and a three.js viewer with territory tint, claim borders, faction names (on by default), and ally, war and vassal relations;
  - Medieval Factions read through its service getters, re-read on its events and on a 30-second poll for changes that fire no event;
  - `/fmap status|stats|reset|recapture`;
  - usage reporting through the fleet's trace client, off until a key is added;
  - CI: unit tests, a bytecode read-only check, the shared Bukkit API compatibility check for 1.21.11, 26.2 and 26.3, and a bot-driven smoke and performance gate on Spigot 26.3.
