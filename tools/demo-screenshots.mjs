// Capture clean screenshots of the live demo (logged in, with seeded data) so the marketing site
// and docs show real screens instead of empty / error states. Headless Chromium via Playwright.
//
// Usage:
//   cd tools && npm install && node demo-screenshots.mjs
// Env (all optional; defaults target the public demo):
//   DEMO_URL   base URL          (default https://app.openwcs.ai)
//   DEMO_USER  login username    (default admin)
//   DEMO_PASS  login password    (default admIn1!)
//   OUT_DIR    output folder     (default ../.demo-screenshots, gitignored)
//   SHOT_WIDTH viewport width    (default 1680)
//
// Output PNGs are captured at deviceScaleFactor 2 (retina). For the website, downscale before
// committing, e.g.:  for f in ../.demo-screenshots/*.png; do sips --resampleWidth 1600 "$f"; done
// then copy the ones you want into public/static/shots/.

import { chromium } from 'playwright';
import { mkdirSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const HERE = dirname(fileURLToPath(import.meta.url));
const URL = process.env.DEMO_URL || 'https://app.openwcs.ai';
const USER = process.env.DEMO_USER || 'admin';
const PASS = process.env.DEMO_PASS || 'admIn1!';
const OUT = process.env.OUT_DIR || join(HERE, '..', '.demo-screenshots');
const WIDTH = Number(process.env.SHOT_WIDTH || 1680);
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: WIDTH, height: 950 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();

// --- log in (the app's own /login form, backed by Keycloak) ---
await page.goto(URL + '/login', { waitUntil: 'networkidle', timeout: 60000 });
await page.fill('#u', USER);
await page.fill('#p', PASS);
await Promise.all([page.waitForLoadState('networkidle'), page.click('button[type=submit]')]);
await page.waitForTimeout(3000);
console.log('logged in ->', page.url());

async function shot(name, route, { wait = 2500, prep } = {}) {
  try {
    await page.goto(URL + route, { waitUntil: 'networkidle', timeout: 60000 });
    await page.waitForTimeout(wait);
    if (prep) await prep();
    await page.waitForTimeout(1000);
    await page.screenshot({ path: join(OUT, `${name}.png`) });
    console.log('OK   ', name);
  } catch (e) {
    console.log('FAIL ', name, e.message.split('\n')[0]);
  }
}

// Load the seeded ACTIVE stock-check definition into the designer.
const loadStockCheck = async () => {
  const exp = page.locator('text=/Existing definitions/i').first();
  if (await exp.count()) { await exp.click().catch(() => {}); await page.waitForTimeout(600); }
  const sc = page.locator('text=/stock-check v1/i').first();
  if (await sc.count()) { await sc.click().catch(() => {}); await page.waitForTimeout(1800); }
};

// --- process designer + verify ---
await shot('process-designer', '/process-design', { prep: async () => {
  await loadStockCheck();
  const step = page.locator('text=scanSku').first();
  if (await step.count()) await step.click().catch(() => {});
  await page.waitForTimeout(800);
} });

// Verify dialog: build a throwaway draft client-side (never saved) and open the wizard.
await shot('verify-dialog', '/process-design', { prep: async () => {
  const addText = page.locator('button:has-text("Text input")').first();
  if (await addText.count()) { await addText.click().catch(() => {}); await page.waitForTimeout(700); }
  for (let i = 0; i < 2; i++) {
    const av = page.locator('button:has-text("Add variable")').first();
    if (await av.count()) { await av.click().catch(() => {}); await page.waitForTimeout(300); }
  }
  const stp = page.locator('.op-pd-step').first();
  if (await stp.count()) { await stp.click().catch(() => {}); await page.waitForTimeout(500); }
  const toggle = page.locator('text=/Verify the scanned value exists/i').first();
  if (await toggle.count()) { await toggle.click().catch(() => {}); await page.waitForTimeout(1000); }
} });

await shot('process-instances', '/process-instances');

// --- dashboards ---
await shot('dashboard-overview', '/');
await shot('dashboard-inbound', '/dashboards/inbound');
await shot('dashboard-outbound', '/dashboards/outbound');
await shot('dashboard-replenishment', '/dashboards/replenishment');
await shot('dashboard-stock', '/dashboards/stock');
await shot('dashboard-abc', '/dashboards/abc');

// --- master data + inventory ---
await shot('master-data-skus', '/master-data/skus');
await shot('master-data-locations', '/master-data/locations');
await shot('handling-units', '/handling-units');
await shot('stock-overview', '/stock-overview');

// --- twin + automation ---
await shot('hardware-twin', '/hardware-twin', { wait: 6000 });
await shot('automation-topology', '/topology', { wait: 6000 });
await shot('equipment', '/master-data/equipment');
await shot('slotting', '/slotting');

await browser.close();
console.log('done ->', OUT);
