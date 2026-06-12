// Live per-conveyor state for the hardware twin: pure derivation, no three.js, no React.
//
// Product rule: a conveyor wears its state as a skin over the belt (the floating orb stays only
// for non-conveyor equipment):
//   GREEN  = functional: no fault, normal (or no) traffic
//   ORANGE = jam or heavy traffic, derived from data the twin already has:
//              (a) a stalled tote: its scan timeline (motion.ts buffer) shows it has not advanced
//                  for clearly longer than its expected hop time,
//              (b) tote density above a threshold (totes per metre of the belt's path length),
//              (c) a HELD divert decision on a tote currently riding this belt (the trace's
//                  result decisions, surfaced by deriveTwin as ToteView.decisions)
//   RED    = faulted: an active device task on this equipment reported FAILED (the same signal
//            that drives the red orb today, twin.ts activity state 'faulted')
// Priority: red over orange over green. The RED signal stays in twin.ts; this module derives the
// ORANGE (jam) signal. Hysteresis (JAM_HOLD_MS) is applied by the consumer so one borderline poll
// does not strobe the belt.
//
// Attribution: a tote's "current conveyor" comes from its current path position, the belt its
// latest timeline point projects onto (motion.ts buildBeltLocator, the same projection the path
// resolver follows), falling back to its anchor placement when no timeline exists.

import { TELEPORT_DIST_M, type LocateBelt, type ToteTimeline } from './motion'
import type { PlacementGeom, ToteView } from './twin'

// ---------------------------------------------------------------------------------------------
// Jam thresholds
// ---------------------------------------------------------------------------------------------

/** Heavy-traffic density: totes per metre of belt path length. 0.5/m means one 0.6 m tote per
 *  2 m of belt, visibly packed with little headway between totes, a sensible congestion line
 *  for the emulator's 0.5 m/s belts. */
export const DENSITY_JAM_PER_M = 0.5

/** Density alone never flags a belt with fewer totes than this (one tote parked on a short spur
 *  is not "traffic"). */
export const DENSITY_MIN_TOTES = 2

/** A tote counts as stalled once nothing new has been scanned for this multiple of its expected
 *  hop time. 2.5x: a single slow hop stays green; a genuine stop goes orange. */
export const STALL_FACTOR = 2.5

/** Expected hop duration fallback when the timeline is too short to estimate one (the emulator
 *  hops roughly every 2-4 s at 0.5 m/s), plus sanity clamps for the estimate. */
export const STALL_FALLBACK_HOP_MS = 4000
const STALL_HOP_MIN_MS = 1500
const STALL_HOP_MAX_MS = 10_000

/** Jam hysteresis: once a belt's jam signal fires it stays orange this long after the raw signal
 *  clears, so a borderline reading (one slow hop, a tote crossing the density line) does not
 *  strobe the belt poll to poll. */
export const JAM_HOLD_MS = 4000

export type ConveyorLiveState = 'ok' | 'jam' | 'fault'

/** Expected hop duration for a tote: the last completed (non-teleport) hop in its timeline,
 *  clamped to sane bounds; fallback when the timeline has fewer than two usable points. */
function expectedHopMs(tl: ToteTimeline): number {
  const pts = tl.points
  for (let i = pts.length - 1; i > 0; i--) {
    const dt = pts[i].tMs - pts[i - 1].tMs
    if (dt <= 50) continue
    const chord = Math.hypot(pts[i].xz[0] - pts[i - 1].xz[0], pts[i].xz[1] - pts[i - 1].xz[1])
    if (chord <= 0 || chord >= TELEPORT_DIST_M) continue
    return Math.max(STALL_HOP_MIN_MS, Math.min(STALL_HOP_MAX_MS, dt))
  }
  return STALL_FALLBACK_HOP_MS
}

/** True when the tote's latest decision is a HOLD (held/queued at a divert). Only the LAST
 *  decision counts: an old hold earlier in the same transport is history, not a live jam. */
function heldNow(tote: ToteView): boolean {
  const d = tote.decisions
  if (!d || !d.length) return false
  const last = d[d.length - 1]
  return last.event === 'HELD' || last.decision === 'HOLD'
}

/**
 * Conveyor placement ids whose live jam signal is RAW-on this instant. Inputs are exactly what
 * the twin already holds: the derived totes (deriveTwin), the per-tote scan timelines (motion.ts
 * buffer fed by useLiveTwin), the conveyor placement geometry, and the belt locator.
 */
export function deriveConveyorJamIds(args: {
  totes: ToteView[]
  timelines?: Map<string, ToteTimeline>
  /** Conveyor placements only (the caller filters via the editor's isConveyor classification). */
  conveyorGeoms: PlacementGeom[]
  locate: LocateBelt
  nowMs: number
}): Set<string> {
  const { totes, timelines, conveyorGeoms, locate, nowMs } = args
  const conveyorIds = new Set(conveyorGeoms.map((g) => g.id))
  const jams = new Set<string>()
  const countByBelt = new Map<string, number>()

  for (const tote of totes) {
    // Only totes actually riding belts: queued totes sit at stations, done totes are leaving.
    if (tote.state !== 'in-transit' && tote.state !== 'recirculating') continue

    // Attribution: the belt the tote's current path position follows; anchor fallback.
    const tl = timelines?.get(tote.huId)
    const lastPt = tl?.points[tl.points.length - 1]
    let beltId: string | null = lastPt ? locate(lastPt.xz) : null
    if (!beltId && tote.anchorPlacedId && conveyorIds.has(tote.anchorPlacedId)) {
      beltId = tote.anchorPlacedId
    }
    if (!beltId) continue

    countByBelt.set(beltId, (countByBelt.get(beltId) ?? 0) + 1)

    // (a) Stalled tote: it should still be moving (a known next node within gliding distance)
    // but nothing new has been scanned for well past its expected hop time.
    if (lastPt?.nextXZ && tl) {
      const chord = Math.hypot(lastPt.nextXZ[0] - lastPt.xz[0], lastPt.nextXZ[1] - lastPt.xz[1])
      if (chord > 0 && chord < TELEPORT_DIST_M && nowMs - lastPt.tMs > STALL_FACTOR * expectedHopMs(tl)) {
        jams.add(beltId)
      }
    }

    // (c) HOLD decision: the tote is held/queued at a divert on this belt right now.
    if (heldNow(tote)) jams.add(beltId)
  }

  // (b) Density: totes per metre of the belt's path length above the threshold.
  for (const g of conveyorGeoms) {
    const n = countByBelt.get(g.id) ?? 0
    if (n < DENSITY_MIN_TOTES) continue
    const lengthM = (g.cumLen && g.cumLen[g.cumLen.length - 1]) || g.size[0] || 1
    if (n / Math.max(1, lengthM) >= DENSITY_JAM_PER_M) jams.add(g.id)
  }

  return jams
}
