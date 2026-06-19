// Unit test for the demo GTP pick engine. Drives the lifecycle without the React screen:
// arm 5 -> present -> full pick -> short pick -> close -> next -> reset. Run with:
//   npx tsx --test test/pickEngine.test.ts
// (tsx handles the JSON fixture imports and import.meta.env that the engine pulls in.)

import { test } from 'node:test'
import assert from 'node:assert/strict'
import * as engine from '../src/demo/pickEngine'

function station(): string {
  const wps = engine.listWorkplaces('any')
  const picking = wps.find((w) => w.supportedModes.includes('PICKING'))
  assert.ok(picking, 'a PICKING station exists')
  return picking!.id
}

test('arms 5 totes and walks them to QUEUED', async () => {
  engine.reset()
  const sid = station()
  // Immediately after seeding everything is REQUESTED (or already arriving on fast timers).
  const initial = engine.getStationQueue(sid)
  assert.equal(initial.length, 5, '5 totes armed')

  // Wait past the arrival window so at least the first tote is QUEUED.
  await new Promise((r) => setTimeout(r, 5000))
  const q = engine.getStationQueue(sid)
  assert.ok(q.some((e) => e.status === 'QUEUED'), 'at least one tote reached QUEUED')
  // QUEUED first in the ordering.
  assert.equal(q[0].status, 'QUEUED')
})

test('claim, present, full pick, close, advance', async () => {
  engine.reset()
  const sid = engine.claimWorkplace(station()).stationId
  assert.ok(engine.heartbeat().active, 'heartbeat stays active')

  await new Promise((r) => setTimeout(r, 5000))
  let q = engine.getStationQueue(sid)
  const head = q.find((e) => e.status === 'QUEUED')!
  assert.ok(head, 'a head tote is queued')

  const cycle = engine.presentStock(sid, { stockHuId: head.huId, skuId: head.skuId, qty: head.qty })
  assert.equal(cycle.puts.length, 1)
  assert.equal(cycle.puts[0].status, 'OPEN')
  assert.equal(cycle.skuId, head.skuId)

  // Full pick: confirm with no qty.
  const put = engine.confirmPut(cycle.puts[0].id)
  assert.equal(put.status, 'CONFIRMED')
  assert.equal(put.confirmedQty, head.qty)

  const closed = engine.closeCycle(cycle.id)
  assert.equal(closed.status, 'CLOSED')

  const before = engine.getStationQueue(sid).length
  engine.completeQueueEntry(head.id)
  const after = engine.getStationQueue(sid)
  assert.equal(after.length, before - 1, 'completed tote left the queue')
  assert.ok(!after.some((e) => e.id === head.id), 'head no longer present')
})

test('short pick records SHORT', async () => {
  engine.reset()
  const sid = engine.claimWorkplace(station()).stationId
  await new Promise((r) => setTimeout(r, 5000))
  const head = engine.getStationQueue(sid).find((e) => e.status === 'QUEUED' && e.qty > 1)!
  assert.ok(head, 'a multi-unit tote to short')
  const cycle = engine.presentStock(sid, { stockHuId: head.huId, skuId: head.skuId, qty: head.qty })
  const put = engine.confirmPut(cycle.puts[0].id, head.qty - 1)
  assert.equal(put.status, 'SHORT')
  assert.equal(put.confirmedQty, head.qty - 1)
})

test('dirty-tote exception removes the head and advances', async () => {
  engine.reset()
  const sid = engine.claimWorkplace(station()).stationId
  await new Promise((r) => setTimeout(r, 5000))
  const head = engine.getStationQueue(sid).find((e) => e.status === 'QUEUED')!
  const before = engine.getStationQueue(sid).length
  engine.markToteDirty(sid, head.id)
  const after = engine.getStationQueue(sid)
  assert.equal(after.length, before - 1)
  assert.ok(!after.some((e) => e.id === head.id))
})

test('broken-units exception keeps the tote and reports adjusted', async () => {
  engine.reset()
  const sid = engine.claimWorkplace(station()).stationId
  await new Promise((r) => setTimeout(r, 5000))
  const head = engine.getStationQueue(sid).find((e) => e.status === 'QUEUED' && e.qty > 1)!
  const before = engine.getStationQueue(sid).length
  const res = engine.markProductBroken(sid, head.id, 1)
  assert.equal(res.adjusted, 1)
  assert.equal(engine.getStationQueue(sid).length, before, 'tote stays at the station')
})

test('reset re-arms all 5 totes', async () => {
  engine.reset()
  const sid = station()
  await new Promise((r) => setTimeout(r, 5000))
  // Complete a tote, then reset and confirm we are back to 5.
  const head = engine.getStationQueue(sid).find((e) => e.status === 'QUEUED')!
  engine.completeQueueEntry(head.id)
  assert.equal(engine.getStationQueue(sid).length, 4)
  engine.reset()
  assert.equal(engine.getStationQueue(sid).length, 5, 'reset re-armed 5 totes')
})
