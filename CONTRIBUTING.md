# Contributing

## Thank You

Thank you for your interest in contributing to Faction Map! This guide will help you get started.

## Links

- [Website](https://dansplugins.com)
- [Discord](https://discord.gg/xXtuAQ2)

## Requirements

- A GitHub account
- Git installed on your local machine
- JDK 21 and a Java IDE or text editor
- Python 3 (for the read-only check and the smoke gate) and Node.js 22 (for the smoke gate's bots)

## Getting Started

1. Fork the repository and clone your fork: `git clone https://github.com/<your-username>/Faction-Map.git`
2. Build the plugin: `./gradlew build`. The first build downloads the Medieval Factions release jar the integration compiles against into `libs/`.

## Identifying What to Work On

Work items are tracked as [GitHub issues](https://github.com/Dans-Plugins/Faction-Map/issues).

## Making Changes

1. Make sure an issue exists for the work. If not, create one using one of the [issue templates](https://github.com/Dans-Plugins/Faction-Map/issues/new/choose).
2. Create a branch from `main`: `feature/`, `fix/`, `docs/`, `chore/` or `ci/` followed by a short description.
3. Make and test your changes.
4. Open a pull request against `main` with `Closes #<number>` in the description.

Commit subjects use the imperative mood, no trailing period, under about 72 characters. Commits authored with an AI agent carry a `Co-Authored-By` trailer.

Document any new config option in [CONFIG.md](CONFIG.md) and add an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md) in the same pull request.

## Rules that keep the plugin safe to install

The plugin must never change a world or a Medieval Factions save. CI enforces this on the bytecode, and a pull request that needs to relax [`scripts/check_read_only.py`](scripts/check_read_only.py) must say why.

- Only `capture/LoadedChunkReader` may call `World`, `Chunk`, `Block` or `BlockState` methods, and only reads. It checks `isChunkLoaded` before reading a chunk. Never call `getChunkAt` (it loads chunks).
- Medieval Factions is read through its service getters only (`factions/MfReader`), on the main thread, and never through its database or REST API.
- Files are written only under the plugin's data folder, atomically, and must be rebuildable.
- Main-thread work must stay inside the per-tick budget. If a change adds main-thread work, run the smoke gate (below) and quote its numbers in the pull request.

## Testing

- Unit tests: `./gradlew clean test` (Windows: `.\gradlew.bat clean test`).
- Read-only check: `python3 scripts/check_read_only.py --self-test && python3 scripts/check_read_only.py build/libs`.
- Bukkit API check against every version in `minecraft-versions.json`: the last step of the `Build` workflow (`.github/workflows/build.yml`), which downloads `check_api_compat.py` from [release-gates](https://github.com/Dans-Plugins/release-gates) and runs it on `build/libs`.
- Smoke and performance gate on a throwaway server: see the `Smoke` workflow (`.github/workflows/smoke.yml`) for the exact steps, and [`tools/smoke/gate.py`](tools/smoke/gate.py). Never point the bots or the gate at a real server: they create factions, claim land and set off TNT.
- Manual testing with Docker: `./gradlew build`, `cp sample.env .env`, `./up.sh`; rebuild and reload with `./reload-plugin.sh`; stop with `./down.sh`.
