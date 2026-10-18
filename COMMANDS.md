# Commands Reference

All commands need the `factionmap.admin` permission (operators by default).

| Command | What it does |
|---|---|
| `/fmap` or `/fmap status` | Chunks mapped, tiles, chunks loaded, captures waiting, whether Medieval Factions is connected, and the viewer's address. |
| `/fmap stats` | Performance figures: main-thread time per tick, per capture and per Medieval Factions read (µs), tile write time, and block change → tile latency (ms), each as count, mean, p50, p95, p99 and max. |
| `/fmap stats json` | The same figures for every series, as one line of JSON (for scripts over RCON). |
| `/fmap reset` | Forgets the performance figures, so the next ones cover only what happens from now on. |
| `/fmap recapture` | Queues every loaded chunk to be read again. Use it after `/fill`, WorldEdit or a world edit that fired no block events; the slow sweep would catch them eventually. |

The same figures are written to `plugins/FactionMap/stats.json` every 5 seconds.
