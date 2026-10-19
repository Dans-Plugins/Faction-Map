# Configuration Guide

`plugins/FactionMap/config.yml` is created on first start. Restart the server after changing it.

## `world`

The world the map shows (default `world`). If it does not exist, the server's first world is used and a warning is logged. One world per server in this version; the Nether and the End are not mapped.

## `web`

| Key | Default | Meaning |
|---|---|---|
| `web.enabled` | `true` | Run the built-in web server. With `false`, the viewer and tiles are still written to `plugins/FactionMap/web/`, so any web server can serve that folder. |
| `web.host` | `127.0.0.1` | Address to listen on. `127.0.0.1` keeps the map private to the machine. To show it to players, proxy it (nginx, Caddy…) or set `0.0.0.0`, which listens on every interface. There is no login: anyone who can reach the port can see the map. |
| `web.port` | `8199` | Port to listen on. |

The server answers GET and HEAD only, serves nothing outside `web/`, never lists directories or serves dotfiles, and sends `X-Content-Type-Options: nosniff`, a Content-Security-Policy, and `Cache-Control: no-store` on `tiles/index.json`.

## `capture`

| Key | Default | Meaning |
|---|---|---|
| `capture.mainThreadBudgetMicros` | `500` | Most main-thread time per tick, in microseconds, spent reading chunks (a tick is 50,000 µs). The time this plugin's event handlers and Medieval Factions reads used that tick counts against it. |
| `capture.debounceMillis` | `1000` | After a block change, the chunk is re-read once it has been quiet this long… |
| `capture.maxDelayMillis` | `5000` | …or at most this long after its first change. A value below `capture.debounceMillis` is treated as `capture.debounceMillis`. |
| `capture.sweepChunksPerSecond` | `2` | Loaded chunks re-read per second, round-robin, to catch changes that fire no event (`/fill`, WorldEdit, some redstone). With 1,500 loaded chunks, every chunk is re-read about every 12.5 minutes. `0` turns the sweep off. |

## `factions`

Only used when Medieval Factions is installed.

| Key | Default | Meaning |
|---|---|---|
| `factions.showNames` | `true` | Show faction names on the map. `false` shows "Faction A", "Faction B"… instead. |
| `factions.showHomes` | `false` | Show faction homes, as white dots on the Relations layer. Only homes in the mapped world are shown. With `false`, homes are never read and never written to the tiles. |
| `factions.pollSeconds` | `30` | How often Medieval Factions is re-read. Claims, renames, disbands and relation changes fire events and appear within seconds; `/f unclaimall` and colour changes fire none, so they can take this long. Values below `1` are treated as `1`. |

Only factions with at least one claim in the mapped world are shown.

## Usage reporting

| Key | Default | Meaning |
|---|---|---|
| `usage-reporting.enabled` | `true` | Send a `startup` event when the plugin is enabled and a `command` event per `/fmap` use. |
| `usage-reporting.endpoint` | `https://trace.danielstephenson.dev` | Where events go. |
| `usage-reporting.key` | `""` | Identifies this plugin to the trace server. Empty means reporting is off. |

`plugins/trace/config.yml` (created on first start) is shared by every plugin that reports to trace: `enabled: false` there turns all of them off, and anything under `tags:` there is sent with every event. The environment variables `TRACE_USAGE_REPORTING=off` and `DO_NOT_TRACK=1` turn reporting off for the whole server process. See [Usage reporting](README.md#usage-reporting).

## Files the plugin writes

Everything is under `plugins/FactionMap/` and is rebuilt if deleted:

- `web/` – the viewer (rewritten at every start) and `web/tiles/` (tiles and `index.json`);
- `cache/` – `palette.txt` (block colour indices) and one column cache per tile, so a tile can be rewritten whole after a restart. Deleting `palette.txt` discards the tiles and the cache, because they store its indices;
- `stats.json` – performance figures, every 5 seconds.
