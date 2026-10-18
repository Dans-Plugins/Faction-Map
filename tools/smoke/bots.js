// Smoke-gate bots for Faction Map, LOCAL throwaway servers only.
//   node bots.js setup   join 4 bots, create 4 factions with colours, claims and relations, stay online
//   node bots.js colours set each faction's colour flag again (a flag change fires no MF event)
//   node bots.js claim   each bot claims a wider radius around its site (MF allows at most 3 at once) (BOTS=Bob limits who joins)
//   node bots.js stay    join 4 bots at the faction sites and stay online (keeps ~1,500 chunks loaded)
// Env: MC_HOST (127.0.0.1), MC_PORT (25620), BOT_VERSION (26.1, the newest mineflayer 4.39.0 speaks; the server runs ViaVersion/ViaBackwards so it can join 26.2 and 26.3),
//      MINEFLAYER (defaults to this directory's pinned mineflayer: npm ci here first), RCON_* for tools/smoke/rcon.py.
const mineflayer = require(process.env.MINEFLAYER || 'mineflayer')
const { execFileSync } = require('child_process')
const path = require('path')
const sleep = ms => new Promise(r => setTimeout(r, ms))
const rcon = (...c) => {
  const out = execFileSync('python3', [path.join(__dirname, 'rcon.py'), ...c]).toString().trim().replace(/\n/g, ' / ')
  console.log('RCON', c.join(' | '), '->', out)
  return out
}
const plan = [
  { name: 'Alice', faction: 'Northreach', color: '#2f6fb0', at: [8, 0], radius: 3 },
  { name: 'Bob', faction: 'Saltmarsh', color: '#3a9a5b', at: [168, 40], radius: 2 },
  { name: 'Cara', faction: 'Emberfall', color: '#d0342c', at: [-120, 150], radius: 3 },
  { name: 'Dan', faction: 'Hollowmere', color: '#c9a227', at: [72, -136], radius: 2 },
]
const bots = {}
function join (p) {
  return new Promise((resolve, reject) => {
    const bot = mineflayer.createBot({
      host: process.env.MC_HOST || '127.0.0.1', port: +(process.env.MC_PORT || 25620),
      username: p.name, version: process.env.BOT_VERSION || '26.1', auth: 'offline'
    })
    // The bots only hold chunks loaded and type commands, so they never send a movement packet:
    // through ViaBackwards, 26.3 rejects every mineflayer 26.1 movement packet as an invalid move
    // and kicks the bot. Teleport confirms still go out, so server-side teleports work.
    const write = bot._client.write.bind(bot._client)
    bot._client.write = (name, params) => /^(position|position_look|look|flying)$/.test(name) ? undefined : write(name, params)
    bot.on('messagestr', m => { const t = m.replace(/\s+/g, ' ').trim(); if (t && t !== 'Wilderness') console.log(p.name, 'MSG', t) })
    bot.on('kicked', r => console.log(p.name, 'KICKED', JSON.stringify(r)))
    bot.on('error', e => console.log(p.name, 'ERROR', e.message))
    bot.on('end', r => console.log(p.name, 'END', r))
    const timer = setTimeout(() => reject(new Error(p.name + ' did not spawn within 60 s')), 60000)
    bot.once('spawn', () => { clearTimeout(timer); bots[p.name] = bot; resolve(bot) })
  })
}
async function say (name, cmd) { console.log(name, 'CMD', cmd); bots[name].chat(cmd); await sleep(2500) }
;(async () => {
  const mode = process.argv[2] || 'stay'
  const only = (process.env.BOTS || '').split(',').filter(Boolean)
  const who = only.length ? plan.filter(p => only.includes(p.name)) : plan
  for (const p of who) {
    await join(p); await sleep(3000)
    rcon(`gamemode creative ${p.name}`, `tp ${p.name} ${p.at[0]} 160 ${p.at[1]}`)
    await sleep(3000)
  }
  if (mode === 'setup') {
    for (const p of plan) {
      rcon(`power set ${p.name} 20`)
      await say(p.name, `/f create ${p.faction}`)
      // Colour before bonus power: on MF 7.0.0 a flag set right after the console's f bonuspower fails
      // with an optimistic-locking error ("Failed to save faction").
      await say(p.name, `/f flag set color ${p.color}`)
      rcon(`f bonuspower ${p.faction} 400`)
      await sleep(2500)
      await say(p.name, `/f claim ${p.radius}`)
    }
    // Northreach and Saltmarsh allied; Emberfall at war with Northreach; Hollowmere vassal of Northreach
    await say('Alice', '/f ally Saltmarsh'); await say('Bob', '/f ally Northreach')
    await say('Cara', '/f declarewar Northreach')
    await say('Alice', '/f vassalize Hollowmere'); await say('Dan', '/f swearfealty Northreach')
    console.log('SETUP DONE')
  }
  if (mode === 'claim') { // grow each joined faction's territory, to watch it appear on the map
    for (const p of who) await say(p.name, `/f claim ${Math.min(3, p.radius + 1)}`)
    console.log('CLAIM DONE')
  }
  if (mode === 'colours') {
    for (const p of plan) await say(p.name, `/f flag set color ${p.color}`)
    console.log('COLOURS DONE')
  }
  console.log('ALL ONLINE')
  setInterval(() => {}, 1 << 30) // stay connected: MF saves synchronously on quit
})().catch(e => { console.error(e); process.exit(1) })
