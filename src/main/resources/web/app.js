// Faction Map viewer. Reads tiles/index.json and tiles/t.X.Z.bin.gz (KFT1, see TileCodec.java) and draws
// terrain plus the Medieval Factions layers. Faction names are player-chosen: they only ever go through
// textContent, never HTML parsing.
"use strict";
(() => {
const $ = id => document.getElementById(id);
const params = new URLSearchParams(location.hash.slice(1));
const POLL_MS = Math.max(1, +params.get("poll") || 5) * 1000;
const canvas = $("view");
let renderer;
try {
  renderer = new THREE.WebGLRenderer({canvas, antialias: true, preserveDrawingBuffer: params.has("still")});
} catch (e) {
  $("nogl").hidden = false; $("panel").hidden = true; $("hud").hidden = true;
  return;
}
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
const scene = new THREE.Scene();
const sky = new THREE.Color(0xb9d6ee);
scene.background = sky;
scene.fog = new THREE.Fog(sky, 900, 2600);
const camera = new THREE.PerspectiveCamera(50, 1, 1, 6000);
scene.add(new THREE.HemisphereLight(0xeaf4ff, 0x5a5040, 0.75));
const sun = new THREE.DirectionalLight(0xffffff, 0.75); sun.position.set(0.5, 1, 0.3); scene.add(sun);
const terrainMat = new THREE.MeshLambertMaterial({vertexColors: true});

let index = null, R = 128, palette = [], factions = [], claimMap = new Map();
const tiles = new Map(); // name -> {mesh, owner, qcx, qcz, base, colorAttr, heights, n, version, tx, tz}
window.FMAP = {swaps: [], ready: false};
window.FMAP.poll = () => poll();

// ------------------------------------------------------------------ data
async function gunzip(buf) {
  const stream = new Blob([buf]).stream().pipeThrough(new DecompressionStream("gzip"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}
async function fetchTile(meta) {
  const r = await fetch("tiles/" + meta.file + "?v=" + meta.version, {cache: "no-store"});
  if (!r.ok) throw new Error("tile " + meta.file + ": HTTP " + r.status);
  return gunzip(await r.arrayBuffer());
}
async function fetchIndex() {
  const r = await fetch("tiles/index.json", {cache: "no-store"});
  if (!r.ok) throw new Error("index.json: HTTP " + r.status);
  return r.json();
}
const hex = s => [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
const key = (x, z) => x + "," + z;

// ------------------------------------------------------------------ tiles
function decodeTile(u8) {
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  if (String.fromCharCode(u8[0], u8[1], u8[2], u8[3]) !== "KFT1") throw new Error("not a KFT1 tile");
  const n = dv.getUint32(4, true), tx = dv.getInt32(8, true), tz = dv.getInt32(12, true);
  let off = 16;
  const dir = u8.subarray(off, off + n); off += n + (n % 2);
  const cols = [];
  for (let i = 0; i < 6; i++) { cols.push(new Int16Array(u8.buffer.slice(u8.byteOffset + off, u8.byteOffset + off + 2 * n))); off += 2 * n; }
  return {n, tx, tz, dir, x: cols[0], z: cols[1], y0: cols[2], du: cols[3], dv: cols[4], c: cols[5]};
}
const NORMALS = [[0, 1, 0], [1, 0, 0], [-1, 0, 0], [0, 0, 1], [0, 0, -1]];
const SHADE = [1.0, 0.86, 0.86, 0.78, 0.78];
function buildTile(t) {
  const n = t.n, pos = new Int16Array(n * 12), nor = new Int8Array(n * 12), col = new Uint8Array(n * 12);
  const idx = new Uint32Array(n * 6), qcx = new Int32Array(n), qcz = new Int32Array(n), base = new Uint8Array(n * 3);
  const heights = new Int16Array(R * R).fill(-32768);
  for (let q = 0; q < n; q++) {
    const d = t.dir[q], x = t.x[q], z = t.z[q], y = t.y0[q], du = t.du[q], dv = t.dv[q];
    let v, ox = x, oz = z; // the column that owns the face, for the claim lookup
    if (d === 0) {
      v = [[x, y, z], [x, y, z + dv], [x + du, y, z + dv], [x + du, y, z]];
      for (let zz = z; zz < z + dv; zz++) heights.fill(y, zz * R + x, zz * R + x + du);
    } else if (d === 1 || d === 2) {
      v = d === 1 ? [[x, y, z], [x, y + dv, z], [x, y + dv, z + du], [x, y, z + du]]
                  : [[x, y, z], [x, y, z + du], [x, y + dv, z + du], [x, y + dv, z]];
      ox = d === 1 ? x - 1 : x;
    } else {
      v = d === 3 ? [[x, y, z], [x + du, y, z], [x + du, y + dv, z], [x, y + dv, z]]
                  : [[x, y, z], [x, y + dv, z], [x + du, y + dv, z], [x + du, y, z]];
      oz = d === 3 ? z - 1 : z;
    }
    const nn = NORMALS[d] || NORMALS[0];
    for (let k = 0; k < 4; k++) {
      pos.set(v[k], q * 12 + k * 3);
      nor[q * 12 + k * 3] = nn[0] * 127; nor[q * 12 + k * 3 + 1] = nn[1] * 127; nor[q * 12 + k * 3 + 2] = nn[2] * 127;
    }
    const rgb = palette[t.c[q]] || [255, 0, 255], s = SHADE[d] || 1;
    base[q * 3] = rgb[0] * s; base[q * 3 + 1] = rgb[1] * s; base[q * 3 + 2] = rgb[2] * s;
    qcx[q] = t.tx * (R / 16) + Math.floor(Math.max(0, Math.min(R - 1, ox)) / 16);
    qcz[q] = t.tz * (R / 16) + Math.floor(Math.max(0, Math.min(R - 1, oz)) / 16);
    const i0 = q * 4;
    idx.set([i0, i0 + 1, i0 + 2, i0, i0 + 2, i0 + 3], q * 6);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.BufferAttribute(pos, 3));
  g.setAttribute("normal", new THREE.BufferAttribute(nor, 3, true));
  const colorAttr = new THREE.BufferAttribute(col, 3, true);
  g.setAttribute("color", colorAttr);
  g.setIndex(new THREE.BufferAttribute(idx, 1));
  g.computeBoundingSphere();
  const mesh = new THREE.Mesh(g, terrainMat);
  mesh.position.set(t.tx * R, 0, t.tz * R);
  return {mesh, owner: new Int32Array(n), qcx, qcz, base, colorAttr, heights, n, tx: t.tx, tz: t.tz};
}
function recomputeOwners(tile) {
  for (let q = 0; q < tile.n; q++) {
    const f = claimMap.get(key(tile.qcx[q], tile.qcz[q]));
    tile.owner[q] = f === undefined ? -1 : f;
  }
}
function applyTint(tile) {
  const on = $("tint").checked, a = 0.55;
  const c = tile.colorAttr.array, fc = factions.map(f => hex(f.color));
  for (let q = 0; q < tile.n; q++) {
    let r = tile.base[q * 3], g = tile.base[q * 3 + 1], b = tile.base[q * 3 + 2];
    const o = tile.owner[q];
    if (on && o >= 0 && fc[o]) { const f = fc[o]; r = r * (1 - a) + f[0] * a; g = g * (1 - a) + f[1] * a; b = b * (1 - a) + f[2] * a; }
    else if (on && factions.length) { // unclaimed land is muted so territories read whatever their hue
      const l = 0.3 * r + 0.59 * g + 0.11 * b, m = 0.7;
      r = (r * (1 - m) + l * m) * 0.8 + 40; g = (g * (1 - m) + l * m) * 0.8 + 40; b = (b * (1 - m) + l * m) * 0.8 + 40;
    }
    for (let k = 0; k < 4; k++) { c[q * 12 + k * 3] = r; c[q * 12 + k * 3 + 1] = g; c[q * 12 + k * 3 + 2] = b; }
  }
  tile.colorAttr.needsUpdate = true;
}
async function loadTile(name, meta) {
  const built = buildTile(decodeTile(await fetchTile(meta)));
  const old = tiles.get(name);
  if (old) { scene.remove(old.mesh); old.mesh.geometry.dispose(); }
  built.version = meta.version;
  recomputeOwners(built); applyTint(built);
  scene.add(built.mesh);
  tiles.set(name, built);
}
async function loadTiles(names) {
  let next = 0;
  const worker = async () => { while (next < names.length) { const n = names[next++]; try { await loadTile(n, index.tiles[n]); } catch (e) { console.warn(e); } } };
  await Promise.all([worker(), worker(), worker(), worker()]);
}
function heightAt(bx, bz) {
  const tx = Math.floor(bx / R), tz = Math.floor(bz / R), t = tiles.get(`t.${tx}.${tz}`);
  if (!t) return null;
  const h = t.heights[(bz - tz * R) * R + (bx - tx * R)];
  return h === -32768 ? null : h;
}

// ------------------------------------------------------------------ borders
const WALL = 4; // fence height in blocks
const wallMat = new THREE.MeshBasicMaterial({vertexColors: true, transparent: true, opacity: 0.6, side: THREE.DoubleSide, depthWrite: false});
const lineMat = new THREE.LineBasicMaterial({vertexColors: true});
let borders = null;
function dispose(group) { if (group) { scene.remove(group); group.traverse(o => o.geometry && o.geometry.dispose()); } }
function rebuildBorders() {
  dispose(borders); borders = null;
  const lp = [], lc = [], wp = [], wc = [];
  for (const [k, f] of claimMap) {
    if (!factions[f]) continue;
    const [cx, cz] = k.split(",").map(Number), rgb = hex(factions[f].color).map(v => v / 255);
    const top = rgb.map(v => Math.min(1, v * 0.5 + 0.5));
    for (const [ex, ez] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      if (claimMap.get(key(cx + ex, cz + ez)) === f) continue;
      for (let i = 0; i < 16; i++) {
        let bx, bz, a, b;
        if (ex) { bx = cx * 16 + (ex > 0 ? 15 : 0); bz = cz * 16 + i; const px = cx * 16 + (ex > 0 ? 16 : 0); a = [px, bz]; b = [px, bz + 1]; }
        else { bz = cz * 16 + (ez > 0 ? 15 : 0); bx = cx * 16 + i; const pz = cz * 16 + (ez > 0 ? 16 : 0); a = [bx, pz]; b = [bx + 1, pz]; }
        const h = heightAt(bx, bz); if (h === null) continue;
        const p = [[a[0], h, a[1]], [b[0], h, b[1]], [b[0], h + WALL, b[1]], [a[0], h + WALL, a[1]]];
        for (const j of [0, 1, 2, 0, 2, 3]) { wp.push(...p[j]); wc.push(...rgb); }
        lp.push(a[0], h + WALL, a[1], b[0], h + WALL, b[1]); lc.push(...top, ...top);
      }
    }
  }
  const lg = new THREE.BufferGeometry();
  lg.setAttribute("position", new THREE.Float32BufferAttribute(lp, 3));
  lg.setAttribute("color", new THREE.Float32BufferAttribute(lc, 3));
  const wg = new THREE.BufferGeometry();
  wg.setAttribute("position", new THREE.Float32BufferAttribute(wp, 3));
  wg.setAttribute("color", new THREE.Float32BufferAttribute(wc, 3));
  borders = new THREE.Group();
  borders.add(new THREE.Mesh(wg, wallMat), new THREE.LineSegments(lg, lineMat));
  borders.visible = $("borders").checked;
  scene.add(borders);
}

// ------------------------------------------------------------------ factions
let centres = [];
function factionCentre(f) {
  // the centre of the faction's densest 32x32-chunk area: claims can be scattered across the world
  const byArea = new Map();
  for (const [x, z, ff] of index.claims) if (ff === f) { const k = key(x >> 5, z >> 5); byArea.set(k, (byArea.get(k) || 0) + 1); }
  if (!byArea.size) return null;
  const best = [...byArea.entries()].sort((a, b) => b[1] - a[1])[0][0];
  let sx = 0, sz = 0, n = 0;
  for (const [x, z, ff] of index.claims) if (ff === f && key(x >> 5, z >> 5) === best) { sx += x * 16 + 8; sz += z * 16 + 8; n++; }
  const cx = sx / n, cz = sz / n, h = heightAt(Math.round(cx), Math.round(cz));
  return [cx, h === null ? 70 : h, cz, Math.max(220, Math.sqrt(n) * 16 * 3.2)];
}
function relationText(i) {
  const parts = [], name = j => factions[j] ? factions[j].label : "?";
  for (const [a, b, t] of (index.relations || [])) {
    if (t === "war" && (a === i || b === i)) parts.push("at war with " + name(a === i ? b : a));
    if (t === "ally" && (a === i || b === i)) parts.push("allied with " + name(a === i ? b : a));
    if (t === "vassal" && a === i) parts.push("vassal of " + name(b));
    if (t === "vassal" && b === i) parts.push("liege of " + name(a));
  }
  return parts.join("; ");
}
function swatch(color, cls) { const s = document.createElement("span"); s.className = cls || "sw"; s.style.background = color; return s; }
function buildLegend() {
  const lg = $("legend"); lg.replaceChildren();
  $("factions-section").hidden = factions.length === 0;
  factions.forEach((f, i) => {
    const b = document.createElement("button");
    b.type = "button";
    const nm = document.createElement("span"); nm.className = "name"; nm.textContent = f.label + (f.colorAdjusted ? " *" : "");
    const num = document.createElement("span"); num.className = "num"; num.textContent = f.chunks + (f.chunks === 1 ? " chunk" : " chunks");
    b.append(swatch(f.color), nm, num);
    b.title = "Fly to " + f.label;
    b.addEventListener("click", () => { const c = centres[i]; if (c) flyTo(c[0], c[1], c[2], c[3]); });
    lg.appendChild(b);
    const rl = relationText(i);
    if (rl) { const n = document.createElement("div"); n.className = "note"; n.textContent = rl; lg.appendChild(n); }
    if (f.colorAdjusted) {
      const n = document.createElement("div"); n.className = "note";
      n.textContent = `* drawn in a shifted colour: its own (${f.originalColor}) is too close to ${f.colorClashesWith || "another faction"}'s`;
      lg.appendChild(n);
    }
  });
}
let labels = [];
function buildLabels() {
  const host = $("labels"); host.replaceChildren(); labels = [];
  factions.forEach((f, i) => {
    const c = centres[i]; if (!c) return;
    const el = document.createElement("div"); el.className = "flabel";
    const t = document.createElement("span"); t.textContent = f.label;
    el.append(swatch(f.color), t); host.appendChild(el);
    labels.push({el, p: new THREE.Vector3(c[0], c[1] + 12, c[2])});
  });
}
const _v = new THREE.Vector3();
function placeLabels() {
  const on = $("names").checked, w = innerWidth, h = innerHeight;
  for (const L of labels) {
    _v.copy(L.p).project(camera);
    const vis = on && _v.z < 1 && Math.abs(_v.x) < 1.1 && Math.abs(_v.y) < 1.1 && camera.position.distanceTo(L.p) < 2400;
    L.el.style.display = vis ? "" : "none";
    if (vis) { L.el.style.left = ((_v.x + 1) / 2 * w) + "px"; L.el.style.top = ((1 - _v.y) / 2 * h) + "px"; }
  }
}
let relGroup = null;
const REL_RGB = {ally: [0.23, 0.6, 0.36], war: [0.82, 0.2, 0.17], vassal: [0.79, 0.64, 0.15]};
function rebuildRelations() {
  dispose(relGroup); relGroup = null;
  relGroup = new THREE.Group();
  // Arcs are thin tubes rather than GL lines, which are one pixel wide on most platforms.
  for (const [a, b, t] of (index.relations || [])) {
    const A = centres[a], B = centres[b]; if (!A || !B) continue;
    const d = Math.hypot(A[0] - B[0], A[2] - B[2]), lift = 40 + d * 0.35, N = 48;
    const pts = [];
    for (let k = 0; k <= N; k++) {
      const u = k / N;
      pts.push(new THREE.Vector3(A[0] + (B[0] - A[0]) * u, A[1] + (B[1] - A[1]) * u + 4 * lift * u * (1 - u) + 14, A[2] + (B[2] - A[2]) * u));
    }
    const mat = new THREE.MeshBasicMaterial({color: new THREE.Color(...(REL_RGB[t] || [1, 1, 1])), depthTest: false, transparent: true, opacity: 0.9});
    const radius = Math.max(0.8, d / 400);
    // war is dashed, so it reads without colour
    const pieces = t === "war" ? [...Array(N / 4).keys()].map(i => pts.slice(i * 4, i * 4 + 4)) : [pts];
    for (const piece of pieces) {
      if (piece.length < 2) continue;
      relGroup.add(new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(piece), piece.length * 2, radius, 6, false), mat));
    }
  }
  const hp = [];
  factions.forEach(f => { if (Array.isArray(f.home)) hp.push(f.home[0] + 0.5, f.home[1] + 1, f.home[2] + 0.5); });
  if (hp.length) {
    const hg = new THREE.BufferGeometry(); hg.setAttribute("position", new THREE.Float32BufferAttribute(hp, 3));
    relGroup.add(new THREE.Points(hg, new THREE.PointsMaterial({color: 0xffffff, size: 9, sizeAttenuation: false, depthTest: false})));
  }
  relGroup.renderOrder = 10;
  relGroup.visible = $("rels").checked;
  scene.add(relGroup);
}
function applyIndex(next) {
  index = next;
  R = index.tileBlocks || 128;
  palette = (index.palette || []).map(hex);
  factions = index.factions || [];
  claimMap = new Map((index.claims || []).map(([x, z, f]) => [key(x, z), f]));
}
function refreshLayers() {
  centres = factions.map((_, i) => factionCentre(i));
  rebuildBorders(); buildLegend(); buildLabels(); rebuildRelations(); updateText();
}
function updateText() {
  const metas = Object.values(index.tiles || {});
  const chunks = metas.reduce((a, m) => a + m.chunks, 0), kb = metas.reduce((a, m) => a + m.bytes, 0) / 1024;
  const when = new Date(index.generated * 1000).toLocaleString(undefined, {dateStyle: "medium", timeStyle: "short"});
  $("sub").textContent = (factions.length ? `${factions.length} factions · ${(index.claims || []).length} claimed chunks · ` : "")
    + `${chunks.toLocaleString()} chunks mapped · updated ${when}`;
  $("foot").textContent = "Drag to orbit; right-drag or shift-drag to pan; wheel or pinch to zoom. Select a faction to fly to it. "
    + `${metas.length} tiles, ${(kb / 1024).toFixed(1)} MB.`;
  $("empty").hidden = metas.length > 0;
}

// ------------------------------------------------------------------ live updates
let polling = false;
async function poll() {
  if (polling) return;
  polling = true;
  try {
    const next = await fetchIndex();
    const changed = Object.keys(next.tiles).filter(n => !tiles.has(n) || tiles.get(n).version !== next.tiles[n].version);
    const gone = [...tiles.keys()].filter(n => !(n in next.tiles));
    const layersChanged = JSON.stringify([next.claims, next.factions, next.relations, next.palette])
      !== JSON.stringify([index.claims, index.factions, index.relations, index.palette]);
    applyIndex(next);
    for (const n of gone) { dispose(tiles.get(n).mesh); tiles.delete(n); }
    for (const n of changed) { const t0 = performance.now(); try { await loadTile(n, index.tiles[n]); } catch (e) { console.warn(e); } window.FMAP.swaps.push({tile: n, ms: Math.round(performance.now() - t0)}); }
    if (layersChanged) for (const t of tiles.values()) { recomputeOwners(t); applyTint(t); }
    if (changed.length || gone.length || layersChanged) refreshLayers(); else updateText();
  } catch (e) { console.warn(e); }
  finally { polling = false; }
}

// ------------------------------------------------------------------ camera
const ctl = {target: new THREE.Vector3(), dist: 700, theta: 0.8, phi: 0.9};
function placeCamera() {
  const sp = Math.sin(ctl.phi);
  camera.position.set(ctl.target.x + ctl.dist * sp * Math.sin(ctl.theta), ctl.target.y + ctl.dist * Math.cos(ctl.phi),
    ctl.target.z + ctl.dist * sp * Math.cos(ctl.theta));
  camera.lookAt(ctl.target);
}
let anim = null;
function flyTo(x, y, z, dist) {
  const from = {x: ctl.target.x, y: ctl.target.y, z: ctl.target.z, d: ctl.dist}, t0 = performance.now();
  anim = now => {
    const k = Math.min(1, (now - t0) / 900), e = k < .5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
    ctl.target.set(from.x + (x - from.x) * e, from.y + (y - from.y) * e, from.z + (z - from.z) * e);
    ctl.dist = from.d + (dist - from.d) * e; if (k >= 1) anim = null;
  };
}
const pointers = new Map(); let pinch0 = null;
canvas.addEventListener("pointerdown", e => { canvas.setPointerCapture(e.pointerId); pointers.set(e.pointerId, {x: e.clientX, y: e.clientY, b: e.button, s: e.shiftKey}); anim = null; });
canvas.addEventListener("pointerup", e => { pointers.delete(e.pointerId); pinch0 = null; });
canvas.addEventListener("pointercancel", e => { pointers.delete(e.pointerId); pinch0 = null; });
canvas.addEventListener("contextmenu", e => e.preventDefault());
function pan(dx, dy) {
  const s = ctl.dist * 0.0015, f = new THREE.Vector3(Math.sin(ctl.theta), 0, Math.cos(ctl.theta)), r = new THREE.Vector3(f.z, 0, -f.x);
  ctl.target.addScaledVector(r, -dx * s).addScaledVector(f, -dy * s);
}
canvas.addEventListener("pointermove", e => {
  const p = pointers.get(e.pointerId); if (!p) return;
  const dx = e.clientX - p.x, dy = e.clientY - p.y; p.x = e.clientX; p.y = e.clientY;
  if (pointers.size === 2) {
    const [a, b] = [...pointers.values()], d = Math.hypot(a.x - b.x, a.y - b.y);
    if (pinch0) ctl.dist = Math.max(40, Math.min(4000, ctl.dist * pinch0 / d));
    pinch0 = d; pan(dx / 2, dy / 2); return;
  }
  if (p.b === 2 || p.s) pan(dx, dy);
  else { ctl.theta -= dx * 0.005; ctl.phi = Math.max(0.12, Math.min(1.45, ctl.phi - dy * 0.005)); }
});
canvas.addEventListener("wheel", e => { e.preventDefault(); anim = null; ctl.dist = Math.max(40, Math.min(4000, ctl.dist * Math.exp(e.deltaY * 0.001))); }, {passive: false});

// ------------------------------------------------------------------ controls
$("names").addEventListener("change", placeLabels);
$("tint").addEventListener("change", () => { for (const t of tiles.values()) applyTint(t); });
$("borders").addEventListener("change", e => { if (borders) borders.visible = e.target.checked; });
$("rels").addEventListener("change", e => { if (relGroup) relGroup.visible = e.target.checked; });
$("toggle").addEventListener("click", () => {
  const p = $("panel"), collapsed = p.classList.toggle("collapsed");
  $("toggle").textContent = collapsed ? "Show" : "Hide"; $("toggle").setAttribute("aria-expanded", String(!collapsed));
});

// ------------------------------------------------------------------ loop
let frames = 0, fpsT = performance.now(), fps = 0;
function updateHud() {
  const tris = [...tiles.values()].reduce((a, t) => a + t.n * 2, 0);
  $("hud").textContent = `${fps.toFixed(0)} fps · ${(tris / 1e6).toFixed(2)} M tris · x ${ctl.target.x.toFixed(0)} z ${ctl.target.z.toFixed(0)}`;
}
function resize() { const w = innerWidth, h = innerHeight; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix(); }
addEventListener("resize", resize);
function frame(now) {
  if (anim) anim(now);
  placeCamera(); renderer.render(scene, camera); placeLabels();
  frames++; if (now - fpsT > 1000) { fps = frames * 1000 / (now - fpsT); frames = 0; fpsT = now; updateHud(); }
  requestAnimationFrame(frame);
}
(async () => {
  resize();
  try { applyIndex(await fetchIndex()); }
  catch (e) { applyIndex({tiles: {}, palette: [], factions: [], claims: [], relations: [], generated: Date.now() / 1000}); }
  await loadTiles(Object.keys(index.tiles));
  refreshLayers();
  // start over the faction with the most claims, else over the middle of what is mapped
  const metas = Object.values(index.tiles);
  if (centres[0]) { ctl.target.set(centres[0][0], centres[0][1], centres[0][2]); ctl.dist = centres[0][3] * 1.3; }
  else if (metas.length) {
    const mx = metas.reduce((a, m) => a + m.tx, 0) / metas.length, mz = metas.reduce((a, m) => a + m.tz, 0) / metas.length;
    ctl.target.set((mx + 0.5) * R, 70, (mz + 0.5) * R);
  }
  if (params.has("x")) ctl.target.set(+params.get("x"), +(params.get("y") || 70), +params.get("z"));
  if (params.has("d")) ctl.dist = +params.get("d");
  if (params.has("t")) ctl.theta = +params.get("t");
  if (params.has("p")) ctl.phi = +params.get("p");
  if (params.has("still")) $("hud").hidden = true; // no frame rate to show when rendering on demand
  window.FMAP.ready = true;
  if (params.has("still")) { // headless capture: render on demand only (software GL is slow)
    window.FMAP.render = () => { const t0 = performance.now(); placeCamera(); renderer.render(scene, camera); placeLabels(); renderer.getContext().finish(); return performance.now() - t0; };
    return;
  }
  requestAnimationFrame(frame);
  setInterval(poll, POLL_MS);
})();
})();
