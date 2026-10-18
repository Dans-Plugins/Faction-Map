// Screenshots of the viewer in headless Chromium (SwiftShader WebGL). LOCAL use.
//   node shoot.js <url> <out-prefix> <width>x<height> [<view-hash>...]
// A view hash sets the camera, e.g. "x=8&z=0&d=600&t=0.8&p=0.9"; "still" is added so the page renders on demand.
// Env: PLAYWRIGHT = path to an installed playwright module (not a dependency of the bots).
const { chromium } = require(process.env.PLAYWRIGHT || 'playwright')
const [,, url, prefix, size, ...views] = process.argv
const [w, h] = size.split('x').map(Number)
;(async () => {
  const browser = await chromium.launch({ args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] })
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1, isMobile: w < 640, hasTouch: w < 640 })
  page.on('pageerror', e => console.log('pageerror:', e.message))
  page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') console.log('console:', m.type(), m.text()) })
  const list = views.length ? views : ['']
  for (let i = 0; i < list.length; i++) {
    const t0 = Date.now()
    await page.goto('about:blank')
    await page.goto(url + '#still' + (list[i] ? '&' + list[i] : ''))
    await page.waitForFunction(() => window.FMAP && window.FMAP.ready === true, null, { timeout: 180000 })
    const loadMs = Date.now() - t0
    const frameMs = []
    for (let k = 0; k < 2; k++) frameMs.push(await page.evaluate(() => window.FMAP.render()))
    await page.screenshot({ path: `${prefix}-${i}.png`, timeout: 300000 })
    console.log(JSON.stringify({ view: list[i], loadMs, frameMs: frameMs.map(Math.round) }))
  }
  await browser.close()
})()
