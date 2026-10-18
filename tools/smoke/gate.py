#!/usr/bin/env python3
"""Faction Map smoke and performance gate. LOCAL THROWAWAY SERVERS ONLY.

Run against a server that has Faction Map (and normally Medieval Factions) installed, with the bots
from bots.js online. It measures the performance budget from RFC 0002 and exits 1 if it is missed:

  steady   after warm-up, with the bots online and block changes happening:
           p99 of main.tick.us <= --p99-us (1000) and no tick above --max-us (5000)
  latency  end to end: RCON summons TNT at a land spot in a loaded chunk, checks that it exploded, then
           polls index.json over HTTP until the tile's version changes; every trial must reach the served
           tile and the median must be <= --latency-ms (3000)

The areas around the four faction sites are force-loaded first (the bots cannot hold chunks loaded),
so every run has the same loaded set.
  wave     (reported, not gated) 256 unexplored chunks are force-loaded at once and released;
           RFC 0002 proposes p99 <= 2 ms here

Env: RCON_HOST/RCON_PORT/RCON_PASSWORD (see rcon.py). The plugin's stats come from its stats.json,
which it writes every 5 s.
"""

import argparse
import json
import os
import statistics
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rcon  # noqa: E402

# Candidate TNT spots: unclaimed, inside the bots' view distance. Water spots are skipped (TNT under
# water breaks nothing, which is correct but measures nothing).
# The bots cannot keep chunks loaded (they send no movement packets, see bots.js), so the areas around
# the four faction sites are force-loaded: 15 x 15 chunks each, entity-ticking, the same every run.
SITES = [(8, 0), (168, 40), (-120, 150), (72, -136)]
FORCE_RADIUS = 7  # chunks

SPOTS = [(100, 100), (-90, -60), (230, 0), (-40, 230), (140, -180), (-180, 80), (60, 130), (250, 110),
         (-60, -150), (10, -110), (-200, 200), (120, -60)]


def stats(data_dir):
    with open(os.path.join(data_dir, "stats.json"), encoding="utf-8") as f:
        return json.load(f)


def wait_for(cond, timeout, every=1.0, what="condition"):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = cond()
            if v:
                return v
        except (OSError, ValueError, KeyError):
            pass
        time.sleep(every)
    raise TimeoutError(f"timed out waiting for {what}")


def fresh_stats(data_dir, after):
    """stats.json written after `after` (epoch seconds)."""
    p = os.path.join(data_dir, "stats.json")
    wait_for(lambda: os.path.getmtime(p) > after, 30, 0.5, "a fresh stats.json")
    return stats(data_dir)


def index(url):
    with urllib.request.urlopen(url + "tiles/index.json", timeout=10) as r:
        return json.load(r)


def on_water(x, z):
    out = rcon.run(f"execute positioned {x} 0 {z} positioned over world_surface if block ~ ~-1 ~ minecraft:water")[0]
    return "passed" in out.lower()


def chunk_loaded(x, z):
    out = rcon.run(f"execute if loaded {x} 0 {z}")[0]
    return "passed" in out.lower()


def force_load_sites():
    for x, z in SITES:
        cx, cz = x >> 4, z >> 4
        r = FORCE_RADIUS
        print("  " + rcon.run(f"forceload add {(cx - r) * 16} {(cz - r) * 16} {(cx + r) * 16 + 15} {(cz + r) * 16 + 15}")[0])


def latency_trials(url, n, timeout_s=15):
    """Each trial is a block change the plugin must serve. Spots whose chunk is not loaded, or that are
    water, are skipped and listed. A TNT that never explodes is a harness error (the gate fails, and
    says it was the harness), never a pass or a silent skip."""
    results = []
    harness_errors = []
    for x, z in SPOTS:
        if len(results) >= n:
            break
        if not chunk_loaded(x, z):  # checked first: the water test also fails on an unloaded chunk
            print(f"  skip ({x},{z}): chunk not loaded (outside the force-loaded sites)")
            continue
        if on_water(x, z):
            print(f"  skip ({x},{z}): water")
            continue
        name = f"t.{x >> 7}.{z >> 7}"
        before = index(url)["tiles"].get(name, {}).get("version")
        t0 = time.time()
        tag = f"fmgate{len(results)}"
        rcon.run(f"execute positioned {x} 0 {z} positioned over world_surface run summon minecraft:tnt ~ ~ ~ {{fuse:1,Tags:[\"{tag}\"]}}")
        changed = False
        exploded = None
        while time.time() - t0 < timeout_s:
            if index(url)["tiles"].get(name, {}).get("version") != before:
                changed = True
                break
            if exploded is None and time.time() - t0 > 3:
                exploded = "passed" not in rcon.run(f"execute if entity @e[type=minecraft:tnt,tag={tag}]")[0].lower()
                if not exploded:
                    break
            time.sleep(0.05)
        ms = round((time.time() - t0) * 1000)
        if exploded is False:
            rcon.run(f"kill @e[type=minecraft:tnt,tag={tag}]")
            print(f"  TNT at ({x},{z}): HARNESS ERROR - the TNT never exploded (chunk not entity-ticking)")
            harness_errors.append([x, z])
            continue
        print(f"  TNT at ({x},{z}) tile {name}: {'served in ' + str(ms) + ' ms' if changed else 'NO CHANGE in ' + str(timeout_s) + ' s'}")
        results.append(ms if changed else None)
        time.sleep(6)
    return results, harness_errors


def summarise(s, key):
    v = s["stats"].get(key)
    return v or {}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data-dir", required=True, help="the server's plugins/FactionMap")
    ap.add_argument("--url", default="http://127.0.0.1:8199/")
    ap.add_argument("--steady-seconds", type=int, default=300)
    ap.add_argument("--trials", type=int, default=8)
    ap.add_argument("--wave", action="store_true", help="also run a load wave (forceload) and report it")
    ap.add_argument("--p99-us", type=int, default=1000)
    ap.add_argument("--max-us", type=int, default=5000)
    ap.add_argument("--latency-ms", type=int, default=3000)
    ap.add_argument("--out", help="write the results as JSON here")
    a = ap.parse_args()

    print("Force-loading the faction sites...")
    force_load_sites()
    print("Waiting for the cold load to finish (no captures pending)...")
    wait_for(lambda: stats(a.data_dir)["pendingCaptures"] < 20 and stats(a.data_dir)["chunksMapped"] > 0, 900, 2, "warm-up")
    status = stats(a.data_dir)
    print(f"  {status['chunksMapped']} chunks mapped in {status['tiles']} tiles; {status['chunksLoaded']} loaded")
    print(rcon.run("fmap reset")[0])
    t_reset = time.time()

    print(f"Steady state for {a.steady_seconds} s, with {a.trials} TNT latency trials inside it:")
    lat, harness_errors = latency_trials(a.url, a.trials)
    remaining = a.steady_seconds - (time.time() - t_reset)
    if remaining > 0:
        time.sleep(remaining)
    steady = fresh_stats(a.data_dir, time.time())
    tick = summarise(steady, "main.tick.us")

    result = {
        "server": rcon.run("version")[0].splitlines()[0] if rcon.run("version")[0] else "",
        "chunksMapped": steady["chunksMapped"], "chunksLoaded": steady["chunksLoaded"], "tiles": steady["tiles"],
        "steadySeconds": round(time.time() - t_reset),
        "steady": {k: summarise(steady, k) for k in ("main.tick.us", "main.capture.us", "main.mf_read.us",
                                                     "async.tile_write.us", "tile.bytes", "latency.block_event_to_tile.ms")},
        "latencyTrialsMs": lat,
        "harnessErrors": harness_errors,
    }
    ok_lat = [x for x in lat if x is not None]
    checks = {
        f"steady p99 main.tick.us <= {a.p99_us}": tick.get("p99", 1e9) <= a.p99_us,
        f"steady max main.tick.us <= {a.max_us}": tick.get("max", 1e9) <= a.max_us,
        f"median block change -> tile served <= {a.latency_ms} ms": bool(ok_lat) and statistics.median(ok_lat) <= a.latency_ms,
        f"{a.trials} TNT trials ran": len(lat) == a.trials,
        "every TNT trial reached the served tile": bool(lat) and all(x is not None for x in lat),
        "harness: every TNT exploded": not harness_errors,
    }

    if a.wave:
        # The bots cannot move (see bots.js), so the wave is made with /forceload: 256 chunks of land
        # nobody has visited load (and generate) at once, then are released again.
        print("Load wave: force-loading 256 unexplored chunks, then releasing them")
        before = stats(a.data_dir)["chunksMapped"]
        rcon.run("fmap reset")
        rcon.run("forceload add 2000 2000 2255 2255")
        wait_for(lambda: stats(a.data_dir)["chunksMapped"] >= before + 256 and stats(a.data_dir)["pendingCaptures"] < 20,
                 300, 2, "the wave to be mapped")
        time.sleep(10)
        wave = fresh_stats(a.data_dir, time.time())
        rcon.run("forceload remove 2000 2000 2255 2255")
        result["wave"] = {k: summarise(wave, k) for k in ("main.tick.us", "main.capture.us", "async.tile_write.us")}
        result["wave"]["chunksMappedAdded"] = wave["chunksMapped"] - before

    result["checks"] = checks
    print(json.dumps(result, indent=2))
    if a.out:
        with open(a.out, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2)
    failed = [k for k, v in checks.items() if not v]
    for k, v in checks.items():
        print(("PASS " if v else "FAIL ") + k)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
