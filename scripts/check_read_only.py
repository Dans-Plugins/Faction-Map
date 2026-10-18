#!/usr/bin/env python3
"""Read-only check: Faction Map's jar may only *read* the world and Medieval Factions.

The plugin's contract is that installing it can never change a world or a Medieval Factions save.
This script checks that mechanically, on the compiled bytecode (`javap -c -p`), so it holds for what
ships rather than for what the source appears to say:

  guarded-owner   Only `capture/LoadedChunkReader` may call methods on World, Chunk, ChunkSnapshot,
                  Location, RegionAccessor or anything in org.bukkit.block (Block, BlockState...).
  world-allow     Every such call is on an allow-list of reads. Anything else fails, including
                  getChunkAt (it loads chunks), getBlockAt and getChunkSnapshot (not needed).
  loaded-first    In LoadedChunkReader, a method that reads blocks calls World.isChunkLoaded before
                  any other World method, so it can never load or generate a chunk.
  mf-allow        Every call into Medieval Factions (com.dansplugins.factionsystem) is on an allow-list
                  of getters. No save, delete, or anything else.
  forbidden       A deny-list of world-, chunk-, server- and command-mutating methods anywhere in the jar
                  (belt and braces for owners the rules above do not cover).
  no-field-write  No putfield/putstatic on a Bukkit or Medieval Factions class.
  no-db-no-http   No JDBC, H2 or jOOQ (Medieval Factions' database), and no outbound HTTP outside the
                  vendored usage-reporting client (Medieval Factions' REST API is never used).

Usage:
  check_read_only.py PATH_TO_JAR_OR_build/libs     exit 1 and list every violation
  check_read_only.py --self-test                   prove each rule fires on a planted violation
Only the standard library and the JDK's javap are used.
"""

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

PLUGIN_PREFIX = "com/dansplugins/factionmap/"
READER = "com/dansplugins/factionmap/capture/LoadedChunkReader"
TRACE_PREFIX = "com/dansplugins/factionmap/trace/"

GUARDED_PREFIXES = (
    "org/bukkit/World", "org/bukkit/RegionAccessor", "org/bukkit/Chunk", "org/bukkit/ChunkSnapshot",
    "org/bukkit/Location", "org/bukkit/block/", "org/bukkit/generator/",
)

# owner -> methods LoadedChunkReader may call. All reads of already-loaded state.
WORLD_ALLOW = {
    "org/bukkit/World": {"isChunkLoaded", "getHighestBlockYAt", "getType", "getLoadedChunks", "getMinHeight",
                         "getName", "getUID"},
    "org/bukkit/RegionAccessor": {"getType", "getHighestBlockYAt"},
    "org/bukkit/Chunk": {"getX", "getZ", "getWorld"},
    "org/bukkit/block/Block": {"getX", "getZ", "getWorld"},
    "org/bukkit/block/BlockState": {"getX", "getZ", "getWorld"},
}
# World methods a block-reading method may only call after isChunkLoaded
NEEDS_LOADED_CHECK = {"getHighestBlockYAt", "getType"}

MF_PREFIX = "com/dansplugins/factionsystem/"
MF_ALLOW = {
    "com/dansplugins/factionsystem/MedievalFactions": {"getServices", "getFlags"},
    "com/dansplugins/factionsystem/service/Services": {"getFactionService", "getClaimService",
                                                       "getFactionRelationshipService"},
    "com/dansplugins/factionsystem/faction/MfFactionService": {"getFactions"},
    "com/dansplugins/factionsystem/claim/MfClaimService": {"getClaims"},
    "com/dansplugins/factionsystem/faction/MfFaction": {"getId", "getName", "getFlags", "getHome"},
    "com/dansplugins/factionsystem/faction/flag/MfFlagValues": {"get"},
    "com/dansplugins/factionsystem/faction/flag/MfFlags": {"getColor"},
    "com/dansplugins/factionsystem/claim/MfClaimedChunk": {"getWorldId", "getX", "getZ", "getFactionId"},
    "com/dansplugins/factionsystem/area/MfPosition": {"getWorldId", "getX", "getY", "getZ"},
    "com/dansplugins/factionsystem/faction/MfFactionId": {"getValue"},
    "com/dansplugins/factionsystem/relationship/MfFactionRelationshipService": {
        "getAlliesByFactionId", "getFactionsAtWarWithByFactionId", "getVassalsByFactionId"},
}

FORBIDDEN = {
    "org/bukkit/block/Block.setType", "org/bukkit/block/Block.setBlockData", "org/bukkit/block/Block.breakNaturally",
    "org/bukkit/block/BlockState.update", "org/bukkit/World.save", "org/bukkit/World.loadChunk",
    "org/bukkit/World.regenerateChunk", "org/bukkit/World.refreshChunk", "org/bukkit/World.unloadChunk",
    "org/bukkit/World.unloadChunkRequest", "org/bukkit/World.setBlockData", "org/bukkit/World.setType",
    "org/bukkit/World.getChunkAt", "org/bukkit/World.getChunkAtAsync", "org/bukkit/World.setChunkForceLoaded",
    "org/bukkit/World.addPluginChunkTicket", "org/bukkit/World.createExplosion", "org/bukkit/World.spawnEntity",
    "org/bukkit/Chunk.load", "org/bukkit/Chunk.unload", "org/bukkit/Chunk.setForceLoaded",
    "org/bukkit/Chunk.addPluginChunkTicket", "org/bukkit/Chunk.getChunkSnapshot",
    "org/bukkit/Bukkit.dispatchCommand", "org/bukkit/Server.dispatchCommand", "org/bukkit/Bukkit.unloadWorld",
    "org/bukkit/Server.unloadWorld", "org/bukkit/Bukkit.createWorld", "org/bukkit/Server.createWorld",
}

INVOKE = re.compile(r"\binvoke(?:virtual|interface|static|special)\b.*//\s*(?:Interface)?Method\s+([\w/$]+)\.([\w$<>]+):")
FIELD_WRITE = re.compile(r"\b(putfield|putstatic)\b.*//\s*Field\s+([\w/$]+)\.([\w$]+):")
ANY_REF = re.compile(r"//\s*(?:(?:Interface)?Method|Field|class)\s+([\w/$]+)")
CLASS_HEADER = re.compile(r"^(?:[\w$]+ )*(?:class|interface|enum|record) ([\w.$]+)")
METHOD_HEADER = re.compile(r"^  (?!Code:)\S.*[;)]\s*(?:throws [\w.$, ]+)?;?$")
DB_PREFIXES = ("java/sql/", "javax/sql/", "org/h2/", "org/jooq/", "com/zaxxer/hikari/")
HTTP_REFS = ("java/net/HttpURLConnection", "java/net/http/", "java/net/URLConnection")


def check_listing(listing):
    """Violations in a `javap -c -p` listing of one or more classes."""
    problems = []
    cls = None
    method = None
    world_calls = {}  # (cls, method) -> list of World method names in order
    for line in listing.splitlines():
        m = CLASS_HEADER.match(line)
        if m:
            cls = m.group(1).replace(".", "/")
            method = None
            continue
        if cls is None or not cls.startswith(PLUGIN_PREFIX):
            continue
        if METHOD_HEADER.match(line):
            method = line.strip()
            continue
        where = f"{cls} :: {method}"
        for ref in ANY_REF.findall(line):
            if ref.startswith(DB_PREFIXES):
                problems.append(f"no-db-no-http: {where} references {ref}")
            if ref.startswith(HTTP_REFS) and not cls.startswith(TRACE_PREFIX):
                problems.append(f"no-db-no-http: {where} references {ref} outside the usage-reporting client")
        fw = FIELD_WRITE.search(line)
        if fw and (fw.group(2).startswith("org/bukkit/") or fw.group(2).startswith(MF_PREFIX)):
            problems.append(f"no-field-write: {where} writes {fw.group(2)}.{fw.group(3)}")
        inv = INVOKE.search(line)
        if not inv:
            continue
        owner, name = inv.group(1), inv.group(2)
        if f"{owner}.{name}" in FORBIDDEN:
            problems.append(f"forbidden: {where} calls {owner}.{name}")
        if owner.startswith(GUARDED_PREFIXES):
            if cls != READER:
                problems.append(f"guarded-owner: {where} calls {owner}.{name}; only LoadedChunkReader may touch the world")
            elif name not in WORLD_ALLOW.get(owner, set()):
                problems.append(f"world-allow: {where} calls {owner}.{name}, which is not an allow-listed read")
            if owner in ("org/bukkit/World", "org/bukkit/RegionAccessor"):
                world_calls.setdefault((cls, method), []).append(name)
        if owner.startswith(MF_PREFIX) and name not in MF_ALLOW.get(owner, set()):
            problems.append(f"mf-allow: {where} calls {owner}.{name}, which is not an allow-listed Medieval Factions getter")
    for (c, meth), calls in world_calls.items():
        if any(n in NEEDS_LOADED_CHECK for n in calls) and calls[0] != "isChunkLoaded":
            problems.append(f"loaded-first: {c} :: {meth} reads blocks but its first World call is {calls[0]}, not isChunkLoaded")
    return problems


def find_jar(paths):
    jars = []
    for p in paths:
        if os.path.isdir(p):
            jars += [j for j in glob.glob(os.path.join(p, "*.jar"))
                     if not j.endswith(("-sources.jar", "-javadoc.jar", "-plain.jar"))]
        elif p.endswith(".jar"):
            jars.append(p)
    if len(jars) != 1:
        sys.exit(f"expected exactly one plugin jar, found {jars or 'none'}")
    return jars[0]


def javap_listing(jar):
    javap = shutil.which("javap") or (os.path.join(os.environ["JAVA_HOME"], "bin", "javap") if os.environ.get("JAVA_HOME") else None)
    if not javap:
        sys.exit("javap not found: put a JDK on PATH or set JAVA_HOME")
    with zipfile.ZipFile(jar) as z:
        classes = [n[:-6] for n in z.namelist() if n.endswith(".class") and n.startswith(PLUGIN_PREFIX)]
    if not classes:
        sys.exit(f"{jar} contains no {PLUGIN_PREFIX} classes")
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(jar) as z:
            z.extractall(tmp, [c + ".class" for c in classes])
        out = subprocess.run([javap, "-c", "-p", "-cp", tmp] + [c.replace("/", ".") for c in classes],
                             capture_output=True, text=True, check=True)
    return out.stdout, len(classes)


SELF_TEST_CASES = {
    "guarded-owner": """public final class com.dansplugins.factionmap.FactionMap {
  public void onEnable();
    Code:
       1: invokeinterface #2,  1   // InterfaceMethod org/bukkit/World.getName:()Ljava/lang/String;
}""",
    "world-allow": """public final class com.dansplugins.factionmap.capture.LoadedChunkReader {
  public void x(int, int);
    Code:
       1: invokeinterface #2,  3   // InterfaceMethod org/bukkit/World.isChunkLoaded:(II)Z
       2: invokeinterface #3,  3   // InterfaceMethod org/bukkit/World.getBlockAt:(III)Lorg/bukkit/block/Block;
}""",
    "loaded-first": """public final class com.dansplugins.factionmap.capture.LoadedChunkReader {
  public com.dansplugins.factionmap.surface.SurfaceColumns capture(long);
    Code:
       1: invokeinterface #2,  4   // InterfaceMethod org/bukkit/World.getHighestBlockYAt:(IILorg/bukkit/HeightMap;)I
}""",
    "forbidden": """public final class com.dansplugins.factionmap.capture.LoadedChunkReader {
  public void x();
    Code:
       1: invokeinterface #2,  2   // InterfaceMethod org/bukkit/block/Block.setType:(Lorg/bukkit/Material;)V
}""",
    "mf-allow": """public final class com.dansplugins.factionmap.factions.MfReader {
  public void x();
    Code:
       1: invokevirtual #2   // Method com/dansplugins/factionsystem/claim/MfClaimService.delete:(Lcom/dansplugins/factionsystem/claim/MfClaimedChunk;)V
}""",
    "no-field-write": """public final class com.dansplugins.factionmap.factions.MfReader {
  public void x();
    Code:
       1: putstatic #2   // Field org/bukkit/Bukkit.server:Lorg/bukkit/Server;
}""",
    "no-db-no-http": """public final class com.dansplugins.factionmap.factions.MfReader {
  public void x();
    Code:
       1: invokeinterface #2,  2   // InterfaceMethod java/sql/Connection.prepareStatement:(Ljava/lang/String;)Ljava/sql/PreparedStatement;
       2: invokevirtual #3   // Method java/net/HttpURLConnection.connect:()V
}""",
}

CLEAN = """public final class com.dansplugins.factionmap.capture.LoadedChunkReader {
  public com.dansplugins.factionmap.surface.SurfaceColumns capture(long);
    Code:
       1: invokeinterface #2,  3   // InterfaceMethod org/bukkit/World.isChunkLoaded:(II)Z
       2: getstatic     #3         // Field org/bukkit/HeightMap.WORLD_SURFACE:Lorg/bukkit/HeightMap;
       3: invokeinterface #4,  4   // InterfaceMethod org/bukkit/World.getHighestBlockYAt:(IILorg/bukkit/HeightMap;)I
       4: invokeinterface #5,  4   // InterfaceMethod org/bukkit/World.getType:(III)Lorg/bukkit/Material;
}
public final class com.dansplugins.factionmap.trace.TraceClient {
  private void send();
    Code:
       1: invokevirtual #2   // Method java/net/HttpURLConnection.connect:()V
}
public final class com.dansplugins.factionmap.factions.MfReader {
  public void x();
    Code:
       1: invokevirtual #2   // Method com/dansplugins/factionsystem/claim/MfClaimService.getClaims:()Ljava/util/List;
}"""


def self_test():
    failed = False
    clean = check_listing(CLEAN)
    if clean:
        print("FAIL clean listing reported:", *clean, sep="\n  ")
        failed = True
    for rule, listing in SELF_TEST_CASES.items():
        hits = [p for p in check_listing(listing) if p.startswith(rule + ":")]
        print(("ok  " if hits else "FAIL") + f" {rule}: " + (hits[0] if hits else "planted violation not reported"))
        failed |= not hits
    sys.exit(1 if failed else 0)


def main(argv):
    if argv == ["--self-test"]:
        self_test()
    if not argv:
        sys.exit(__doc__)
    jar = find_jar(argv)
    listing, n = javap_listing(jar)
    problems = check_listing(listing)
    if problems:
        print(f"Read-only check FAILED for {os.path.basename(jar)} ({n} classes):")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    world = len(re.findall(r"//\s*(?:Interface)?Method org/bukkit/(?:World|Chunk|block/)", listing))
    mf = len(re.findall(r"//\s*(?:Interface)?Method com/dansplugins/factionsystem/", listing))
    print(f"Read-only check passed for {os.path.basename(jar)}: {n} classes, {world} world/chunk/block call sites "
          f"(all allow-listed reads in LoadedChunkReader), {mf} Medieval Factions call sites (all allow-listed getters).")


if __name__ == "__main__":
    main(sys.argv[1:])
