// Capture a read-only snapshot of the live demo box (app.openwcs.ai) into the demo fixtures.
//
// Logs in with the public password grant (read-only browse), then GETs the headline endpoints the
// demo SPA renders and writes each response to ui/src/demo/fixtures/<name>.json. Lists are capped so
// the bundle stays small. Re-run occasionally to refresh the snapshot; it is NOT part of CI.
//
// Usage:
//   node tools/demo-snapshot.mjs
// Env overrides:
//   OWCS_HOST (default https://app.openwcs.ai), OWCS_USER (admin), OWCS_PASS (admIn1!)
//
// If the box is unreachable the hand-authored fixtures already committed stay in place; this script
// only overwrites what it successfully fetches.

import { writeFileSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HOST = process.env.OWCS_HOST || 'https://app.openwcs.ai'
const USER = process.env.OWCS_USER || 'admin'
const PASS = process.env.OWCS_PASS || 'admIn1!'
const REALM = 'openwcs'
const CLIENT = 'openwcs-web'

const __dirname = dirname(fileURLToPath(import.meta.url))
const FIXTURES = join(__dirname, '..', 'ui', 'src', 'demo', 'fixtures')
mkdirSync(FIXTURES, { recursive: true })

// Cap any array (or {content:[]} page) to keep the bundle small.
function cap(data, n) {
  if (Array.isArray(data)) return data.slice(0, n)
  if (data && Array.isArray(data.content)) return { ...data, content: data.content.slice(0, n) }
  return data
}

async function token() {
  const body = new URLSearchParams({ grant_type: 'password', client_id: CLIENT, username: USER, password: PASS })
  const res = await fetch(`${HOST}/realms/${REALM}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) throw new Error(`login failed: ${res.status} ${await res.text()}`)
  const j = await res.json()
  return j.access_token
}

async function main() {
  const t = await token()
  const auth = { Authorization: `Bearer ${t}` }

  // Resolve the demo warehouse.
  const whRes = await fetch(`${HOST}/api/master-data/warehouses?size=200`, { headers: auth })
  const whPage = await whRes.json()
  const warehouses = whPage.content ?? []
  const wh = warehouses[0]
  if (!wh) throw new Error('no warehouse on the demo box')
  const wid = wh.id
  console.log(`demo warehouse: ${wh.code} (${wid})`)

  // name -> { url, capN }. {WID} is substituted with the demo warehouse id.
  const targets = {
    warehouses: { url: `/api/master-data/warehouses?size=200`, cap: 50 },
    skus: { url: `/api/master-data/skus?size=500`, cap: 60 },
    'handling-unit-types': { url: `/api/master-data/handling-unit-types`, cap: 40 },
    locations: { url: `/api/master-data/locations?size=500&warehouseId={WID}`, cap: 120 },
    areas: { url: `/api/master-data/areas?warehouseId={WID}`, cap: 60 },
    'storage-blocks': { url: `/api/master-data/storage-blocks?warehouseId={WID}`, cap: 40 },
    'alert-thresholds': { url: `/api/master-data/alert-thresholds`, cap: 0 },

    'inventory-stock-overview': { url: `/api/inventory/stock/overview?warehouseId={WID}`, cap: 200 },
    'inventory-handling-units': { url: `/api/inventory/handling-units?warehouseId={WID}`, cap: 200 },
    'inventory-dashboard': { url: `/api/inventory/reports/dashboard?warehouseId={WID}`, cap: 0 },

    'orders-dashboard': { url: `/api/orders/reports/dashboard?warehouseId={WID}`, cap: 0 },
    'orders-sla': { url: `/api/orders/reports/sla?warehouseId={WID}&days=14`, cap: 0 },
    'orders-dispatch': { url: `/api/orders/reports/dispatch?warehouseId={WID}`, cap: 0 },
    'orders-pick-tasks': { url: `/api/orders/pick-tasks?warehouseId={WID}`, cap: 120 },

    'allocation-stock-blocking': { url: `/api/allocation/reports/stock-blocking?warehouseId={WID}`, cap: 0 },

    'slotting-replenishment-dashboard': { url: `/api/slotting/replenishment/dashboard?warehouseId={WID}`, cap: 0 },
    'slotting-velocity-abc': { url: `/api/slotting/velocity/abc?warehouseId={WID}`, cap: 0 },

    'flow-automation-summary': { url: `/api/flow/reports/automation-summary?warehouseId={WID}`, cap: 0 },

    'notification-alerts': { url: `/api/notification/alerts?warehouseId={WID}`, cap: 60 },
    'notification-alerts-health': { url: `/api/notification/alerts/health?warehouseId={WID}&days=14`, cap: 0 },

    'gtp-workplaces': { url: `/api/gtp/workplaces?warehouseId={WID}`, cap: 20 },

    'iam-warehouse-access-me': { url: `/api/iam/warehouse-access/me`, cap: 0 },
  }

  let ok = 0
  let fail = 0
  for (const [name, spec] of Object.entries(targets)) {
    const url = HOST + spec.url.replace('{WID}', encodeURIComponent(wid))
    try {
      const res = await fetch(url, { headers: auth })
      if (!res.ok) {
        console.warn(`  skip ${name}: ${res.status}`)
        fail++
        continue
      }
      let data = await res.json()
      if (spec.cap) data = cap(data, spec.cap)
      writeFileSync(join(FIXTURES, `${name}.json`), JSON.stringify(data, null, 2) + '\n')
      ok++
      console.log(`  ok   ${name}`)
    } catch (e) {
      console.warn(`  fail ${name}: ${e.message}`)
      fail++
    }
  }

  // A couple of SKU cards for the pick-station visuals: take the first few SKUs.
  try {
    const skusRes = await fetch(`${HOST}/api/master-data/skus?size=20`, { headers: auth })
    const skus = (await skusRes.json()).content ?? []
    const cards = {}
    for (const s of skus.slice(0, 8)) {
      const cardRes = await fetch(`${HOST}/api/master-data/skus/${s.id}/card?warehouseId=${encodeURIComponent(wid)}`, { headers: auth })
      if (cardRes.ok) cards[s.id] = await cardRes.json()
    }
    writeFileSync(join(FIXTURES, 'sku-cards.json'), JSON.stringify(cards, null, 2) + '\n')
    console.log(`  ok   sku-cards (${Object.keys(cards).length})`)
    ok++
  } catch (e) {
    console.warn(`  fail sku-cards: ${e.message}`)
    fail++
  }

  // Record the demo warehouse id so the mock/session can use it.
  writeFileSync(join(FIXTURES, 'meta.json'), JSON.stringify({ warehouseId: wid, warehouseCode: wh.code, warehouseName: wh.name }, null, 2) + '\n')
  console.log(`\ndone: ${ok} ok, ${fail} failed/skipped. warehouse id ${wid}`)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
