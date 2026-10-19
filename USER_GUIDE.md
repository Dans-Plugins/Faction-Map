# User Guide

## What the map shows

- **Terrain**: the top surface of every chunk that players have loaded since the plugin was installed, in 3D. Grass, flowers, torches, carpets and other small blocks are looked through, so the ground shows. Water and lava are drawn as surfaces. Areas nobody has visited stay blank: the plugin never loads or generates chunks to fill gaps.
- **Territory**: claimed land tinted in the faction's Medieval Factions colour (`/f flag set color #rrggbb`); unclaimed land is muted so territories stand out. When two factions' colours are too close to tell apart, the faction with fewer claims is drawn in a shifted colour, marked with `*` in the legend.
- **Borders**: translucent fences along the edges of each faction's claims.
- **Faction names**: a label over each faction's densest area. Select a faction in the legend to fly to it.
- **Relations**: arcs between factions: green for allies, dashed red for war, gold from a vassal to its liege. The legend lists each faction's relations in words.
- **Homes** (off by default; `factions.showHomes`): a white dot at each faction's home, if it is in the mapped world. The dots belong to the Relations layer and are hidden with it.

Each layer can be switched off in the panel. On a phone the panel is a sheet at the bottom of the screen; **Hide** collapses it.

## Moving around

Drag to orbit; right-drag or shift-drag to pan; scroll or pinch to zoom.

## How it stays up to date

- A chunk is read when it first loads, and again about a second after blocks in it stop changing (at most 5 seconds after the first change). Only that chunk's tile is rewritten. Open viewers poll every 5 seconds and swap in changed tiles.
- Claims, unclaims, faction creation, renames, disbands and relation changes appear within seconds. `/f unclaimall` and colour changes fire no event in Medieval Factions, so they appear at the next poll (30 seconds by default).
- Changes that fire no block event (`/fill`, WorldEdit, some redstone) are caught by a slow sweep of loaded chunks, or at once with `/fmap recapture`.

## Performance

The plugin reads chunks on the main thread, within `capture.mainThreadBudgetMicros` per tick (500 µs by default), and does everything else on its own threads. `/fmap stats` shows what it actually costs on your server.
