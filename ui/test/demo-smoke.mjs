// Playwright smoke test for the public live demo bundle. Loads the built demo (served by
// `vite preview` under /demo-app/), asserts the dashboard renders with mock data and no login, then
// drives one FULL and one SHORT pick at the GTP station.
//
// How to run:
//   cd ui
//   npm run build:demo                 # produces dist/ at base /demo-app/
//   npm run preview:demo &             # serves http://localhost:4173/demo-app/
//   node test/demo-smoke.mjs           # (override base URL with DEMO_URL=...)
//
// This is intentionally a standalone script, not wired into CI: a full browser e2e is flaky in
// headless CI sandboxes. The pickEngine unit test (npm run test:demo) is the deterministic guard;
// this script is for local confirmation.

import { chromium } from 'playwright'

const BASE = process.env.DEMO_URL || 'http://localhost:4173/demo-app/'

function log(msg) {
  console.log(`[smoke] ${msg}`)
}

async function main() {
  const browser = await chromium.launch()
  const page = await browser.newPage()
  const errors = []
  page.on('pageerror', (e) => errors.push(String(e)))

  log(`opening ${BASE}`)
  await page.goto(BASE, { waitUntil: 'networkidle' })

  // No login: the demo banner and the app shell render, not the Login screen.
  await page.waitForSelector('text=Live demo', { timeout: 15000 })
  log('demo banner present (no login)')

  // The sidebar shell with menu sections renders (read-only landing on the dashboard).
  await page.waitForSelector('.app-shell', { timeout: 15000 })
  log('app shell rendered')

  // Go to the GTP pick station.
  await page.goto(BASE + 'gtp', { waitUntil: 'networkidle' })
  // Open the picking workplace (PP1). The launcher shows an "Open workplace" button.
  await page.waitForSelector('button:has-text("Open workplace")', { timeout: 15000 })
  const openBtn = page.locator('button:has-text("Open workplace")').first()
  await openBtn.click()
  log('opened the pick station')

  // Wait for a tote to arrive and auto-present (the Confirm put button appears).
  await page.waitForSelector('button:has-text("Confirm put")', { timeout: 20000 })
  log('first tote presented')

  // FULL pick.
  await page.locator('button:has-text("Confirm put")').first().click()
  await page.waitForSelector('button:has-text("Finish & close cycle")', { timeout: 10000 })
  await page.locator('button:has-text("Finish & close cycle")').click()
  log('FULL pick confirmed and cycle closed')

  // Next tote presents; do a SHORT pick. Enter a short qty then click Short.
  await page.waitForSelector('button:has-text("Confirm put")', { timeout: 20000 })
  const shortInput = page.locator('input[placeholder="short qty"]').first()
  await shortInput.fill('1')
  await page.locator('button:has-text("Short")').first().click()
  log('SHORT pick confirmed')

  if (errors.length) {
    console.error('[smoke] page errors:', errors)
    throw new Error('page errors occurred')
  }

  log('PASS')
  await browser.close()
}

main().catch(async (e) => {
  console.error('[smoke] FAIL', e)
  process.exit(1)
})
