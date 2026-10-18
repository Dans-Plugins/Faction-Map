// Records viewer frames while the map updates live, for a README GIF. LOCAL use.
//   node record.js <url> <out-dir> <width>x<height> <seconds> <view-hash>
// Env: PLAYWRIGHT = path to an installed playwright module.
const { chromium } = require(process.env.PLAYWRIGHT || 'playwright')
const fs = require('fs')
const [,, url, dir, size, seconds, view] = process.argv
const [w, h] = size.split('x').map(Number)
;(async () => {
  fs.mkdirSync(dir, { recursive: true })
  const browser = await chromium.launch({ args: ['--no-sandbox', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] })
  const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 })
  page.on('pageerror', e => console.log('pageerror:', e.message))
  await page.goto(url + '#still&poll=1&' + view)
  await page.waitForFunction(() => window.FMAP && window.FMAP.ready === true, null, { timeout: 180000 })
  // "still" stops the render loop; poll on our own clock and render each frame on demand
  await page.evaluate(() => { window.FMAP.render() })
  const end = Date.now() + (+seconds) * 1000
  let i = 0
  console.log('RECORDING')
  while (Date.now() < end) {
    await page.evaluate(() => window.FMAP.poll && window.FMAP.poll()).catch(() => {})
    await page.evaluate(() => window.FMAP.render())
    await page.screenshot({ path: `${dir}/f${String(i++).padStart(4, '0')}.png` })
  }
  console.log('FRAMES', i)
  await browser.close()
})()
