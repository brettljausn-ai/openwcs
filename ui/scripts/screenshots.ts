// Screenshot gallery generator.
//
// Builds nothing itself — assumes `vite build` already produced ui/dist — then serves the SPA
// with `vite preview`, logs in headlessly by injecting a fake ADMIN session into sessionStorage
// (the UI only *reads* JWT claims; the gateway verifies them, so an unsigned token is enough to
// render every screen), and captures one PNG per route into the repo-root `screenshots/` folder.
//
// There is no backend in CI, so `/api/*` calls fail gracefully and screens render their empty /
// loading states — the gallery shows each screen's chrome and layout, not live data.
//
// Run from the ui/ directory: `npm run screenshots` (CI does `npm run build` first).
import { spawn, type ChildProcess } from 'node:child_process'
import { mkdir, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium, type Browser } from 'playwright'
import { SCREENS } from '../src/auth/screens'

const PORT = 4173
const BASE = `http://localhost:${PORT}`
const VIEWPORT = { width: 1440, height: 900 }
const HERE = path.dirname(fileURLToPath(import.meta.url))
const OUT_DIR = path.resolve(HERE, '..', '..', 'screenshots') // repo-root/screenshots

/** A fake, unsigned ADMIN session matching the shape AuthContext persists under `openwcs.auth`.
 *  ADMIN bypasses every screen-access check, so all routes are reachable. */
function fakeSession(): string {
  const roles = ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER']
  const b64url = (o: unknown) => Buffer.from(JSON.stringify(o)).toString('base64url')
  const exp = Math.floor(Date.now() / 1000) + 3600 // 1h — far longer than a capture run
  const token = [
    b64url({ alg: 'none', typ: 'JWT' }),
    b64url({ preferred_username: 'demo', name: 'Demo Admin', realm_access: { roles }, exp }),
    'unsigned',
  ].join('.')
  return JSON.stringify({ token, username: 'demo', name: 'Demo Admin', roles })
}

/** Spawn `vite preview` and resolve once it answers, so navigation doesn't race the server. */
async function startPreview(): Promise<ChildProcess> {
  const child = spawn('npm', ['run', 'preview', '--', '--port', String(PORT), '--strictPort'], {
    cwd: path.resolve(HERE, '..'),
    stdio: 'inherit',
  })
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    try {
      const res = await fetch(BASE)
      if (res.ok) return child
    } catch {
      /* not up yet */
    }
    await new Promise((r) => setTimeout(r, 300))
  }
  child.kill()
  throw new Error(`vite preview did not come up on ${BASE} within 30s`)
}

interface Shot {
  key: string
  label: string
  file: string
  section: string
}

async function capture(browser: Browser): Promise<Shot[]> {
  const context = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 1 })
  // Seed the auth session before any app script runs, on every navigation.
  await context.addInitScript((value: string) => {
    window.sessionStorage.setItem('openwcs.auth', value)
  }, fakeSession())
  const page = await context.newPage()

  const shots: Shot[] = []
  // The login screen is captured without a session (separate, token-free context below).
  for (const s of SCREENS) {
    const file = `${s.key.replace(/[^a-z0-9]+/gi, '-')}.png`
    const url = `${BASE}${s.path}`
    process.stdout.write(`  → ${s.path} `)
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30_000 })
      await page.waitForLoadState('networkidle', { timeout: 8_000 }).catch(() => {})
      await page.waitForTimeout(1_200) // settle animations / 3D canvases
      await page.screenshot({ path: path.join(OUT_DIR, file) })
      shots.push({ key: s.key, label: s.label, file, section: s.section ?? 'General' })
      console.log('✓')
    } catch (err) {
      console.log(`✗ ${(err as Error).message}`)
    }
  }
  await context.close()

  // Login screen — no injected session, so the route guard shows /login.
  const loginCtx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 1 })
  const loginPage = await loginCtx.newPage()
  try {
    await loginPage.goto(`${BASE}/login`, { waitUntil: 'networkidle', timeout: 30_000 })
    await loginPage.waitForTimeout(1_200)
    await loginPage.screenshot({ path: path.join(OUT_DIR, 'login.png') })
    shots.unshift({ key: 'login', label: 'Login', file: 'login.png', section: 'General' })
    console.log('  → /login ✓')
  } catch (err) {
    console.log(`  → /login ✗ ${(err as Error).message}`)
  }
  await loginCtx.close()
  return shots
}

/** A Markdown gallery index that renders inline on GitHub, grouped by sidebar section. */
async function writeGallery(shots: Shot[]): Promise<void> {
  const order = ['General', 'Master data', 'Operations', 'Engineering', 'Configuration', 'Administration']
  const bySection = new Map<string, Shot[]>()
  for (const s of shots) {
    const list = bySection.get(s.section) ?? []
    list.push(s)
    bySection.set(s.section, list)
  }
  const sections = [...bySection.keys()].sort((a, b) => order.indexOf(a) - order.indexOf(b))

  const lines: string[] = [
    '# UI screenshot gallery',
    '',
    '> Auto-generated by CI on every merge to `main` — do not edit by hand.',
    `> Source: \`ui/scripts/screenshots.ts\` · ${shots.length} screens · viewport ${VIEWPORT.width}×${VIEWPORT.height}.`,
    '> Captured against the built UI with no backend, so screens show empty/loading states.',
    '',
  ]
  for (const section of sections) {
    lines.push(`## ${section}`, '')
    for (const s of bySection.get(section)!) {
      lines.push(`### ${s.label}`, '', `![${s.label}](${s.file})`, '')
    }
  }
  await writeFile(path.join(OUT_DIR, 'README.md'), lines.join('\n'))
}

async function main(): Promise<void> {
  await rm(OUT_DIR, { recursive: true, force: true })
  await mkdir(OUT_DIR, { recursive: true })

  const preview = await startPreview()
  const browser = await chromium.launch()
  try {
    console.log(`Capturing ${SCREENS.length} screens → ${OUT_DIR}`)
    const shots = await capture(browser)
    await writeGallery(shots)
    console.log(`Done: ${shots.length} screenshots + README.md`)
  } finally {
    await browser.close()
    preview.kill()
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
