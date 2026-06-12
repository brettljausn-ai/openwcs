// Pure tote-motion math for the hardware twin — no three.js, no React, just reviewable functions.
//
// Why this exists (the "tote stutters then jumps" complaint): the twin POLLS the trace feed every
// couple of seconds while the emulator advances roughly one scan hop every ~2 s, so deriving the
// position from only the LATEST scan makes the tote freeze between polls and leap several hops
// when data lands. The fix is the classic interpolation buffer (as used in networked games):
//
//   1. keep a per-tote TIMELINE of timestamped waypoints (every SCANNED trace row: node position,
//      routed-to next node, timestamp),
//   2. render the tote at `now - RENDER_DELAY_MS` and interpolate between the two buffered
//      waypoints that bracket that render time — we only ever interpolate between KNOWN points,
//      so motion is continuous and honest,
//   3. follow the conveyor's polyline geometry between the two points (not the straight chord),
//   4. when the buffer runs dry (no fresh poll yet), dead-reckon gently toward the already-known
//      next node and ease to a stop on arrival instead of freezing mid-belt,
//   5. when revised data moves the target, blend the error away over ~0.5 s instead of snapping.
//
// Genuine discontinuities (RETRIEVED out of the rack, STORE into a cell, induction) still
// teleport: that is real, and interpolating across them would invent motion through walls.

export type XZ = [number, number]

// ----------------------------------------------------------------------------------------------------
// Tuning constants
// ----------------------------------------------------------------------------------------------------

/** How far behind real time tote motion renders (ms).
 *  Trade-off: the delay must exceed one poll interval (plus fetch latency) so that by the time the
 *  render clock reaches a scan, the NEXT scan has usually already been buffered — then motion is
 *  pure interpolation between known points and never stalls. Larger = smoother under poll jitter
 *  but more stale; smaller = fresher but underruns (dead reckoning) more often. With the twin's
 *  2 s poll, 3.5 s leaves ~1.5 s of slack for a slow poll and minor client/server clock skew. */
export const RENDER_DELAY_MS = 3500

/** Fallback belt speed when the timeline is too short to estimate one (the emulator's nominal
 *  0.5 m/s walk speed — see SCAN_SPEED_MPS in twin.ts). */
const FALLBACK_SPEED_MPS = 0.5

/** A hop longer than this is a genuine discontinuity (rack store/retrieve, induction): the tote
 *  teleports rather than glides. */
export const TELEPORT_DIST_M = 6

/** Bracketing scans further apart than this in time mean the tote genuinely dwelled (held at a
 *  divert, waiting for slotting): hold at the earlier point instead of creeping for minutes. */
const MAX_SEG_GAP_MS = 20_000

/** Dead reckoning eases to a stop over (up to) this distance before the known next node. */
const EASE_OUT_M = 0.5

/** Speed-estimate clamps — guard against nonsense from duplicate/near-duplicate timestamps. */
const MIN_SPEED_MPS = 0.05
const MAX_SPEED_MPS = 3

/** Per-tote timeline cap (a live transport is tens of scans; this only bounds pathology). */
const MAX_TIMELINE_POINTS = 64

/** Nodes project onto a conveyor polyline within this distance to count as "on that belt"
 *  (node positions are staged FROM the path waypoints, so the true distance is ~0). */
const ON_BELT_TOL_M = 0.6

function dist(a: XZ, b: XZ): number {
  return Math.hypot(b[0] - a[0], b[1] - a[1])
}

// ----------------------------------------------------------------------------------------------------
// Polyline paths — build, measure, sample
// ----------------------------------------------------------------------------------------------------

export interface PathSample {
  pts: XZ[]
  /** Cumulative arc length; cum[i] is the distance at pts[i], cum[last] === total. */
  cum: number[]
  total: number
}

/** Resolve the travel path between two world points (a conveyor polyline or the plain chord). */
export type PathBetween = (a: XZ, b: XZ) => PathSample

/** Build a path from raw points, dropping consecutive (near-)duplicates. */
export function makePath(raw: XZ[]): PathSample {
  const pts: XZ[] = []
  for (const p of raw) {
    const prev = pts[pts.length - 1]
    if (!prev || dist(prev, p) > 1e-3) pts.push(p)
  }
  if (pts.length < 2) {
    const p = pts[0] ?? ([0, 0] as XZ)
    return { pts: [p, p], cum: [0, 0], total: 0 }
  }
  const cum = [0]
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + dist(pts[i - 1], pts[i]))
  return { pts, cum, total: cum[cum.length - 1] }
}

export function chordPath(a: XZ, b: XZ): PathSample {
  return makePath([a, b])
}

/** Point at arc length `s` (clamped to [0, total]) along a path. */
export function pointAtLen(path: PathSample, s: number): XZ {
  const { pts, cum, total } = path
  if (total <= 0) return pts[0]
  const target = Math.max(0, Math.min(total, s))
  let i = 1
  while (i < cum.length - 1 && cum[i] < target) i++
  const seg = cum[i] - cum[i - 1] || 1
  const f = (target - cum[i - 1]) / seg
  return [pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * f, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * f]
}

// ----------------------------------------------------------------------------------------------------
// Conveyor-geometry resolver — totes ride the drawn belt, not the straight chord
// ----------------------------------------------------------------------------------------------------

/** The slice of PlacementGeom (twin.ts) the resolver needs — structural, to keep this module pure. */
export interface ConveyorGeomLike {
  worldPath?: XZ[]
  cumLen?: number[]
  closed?: boolean
}

interface PreparedBelt {
  pts: XZ[] // closed loops get the seam point appended so the closing segment is walkable
  cum: number[]
  total: number
  closed: boolean
}

function projectOnto(belt: PreparedBelt, p: XZ): { s: number; d: number } {
  const { pts, cum } = belt
  let bestD = Infinity
  let bestS = 0
  for (let i = 1; i < pts.length; i++) {
    const ax = pts[i - 1][0]
    const az = pts[i - 1][1]
    const dx = pts[i][0] - ax
    const dz = pts[i][1] - az
    const len2 = dx * dx + dz * dz
    const t = len2 > 0 ? Math.max(0, Math.min(1, ((p[0] - ax) * dx + (p[1] - az) * dz) / len2)) : 0
    const d = Math.hypot(p[0] - (ax + dx * t), p[1] - (az + dz * t))
    if (d < bestD) {
      bestD = d
      bestS = cum[i - 1] + Math.sqrt(len2) * t
    }
  }
  return { s: bestS, d: bestD }
}

/** Sub-polyline from arc-length station sa to sb (a/b are the exact endpoints to honour). On a
 *  closed loop with sb < sa the walk wraps FORWARD past the seam — belt direction follows the
 *  drawn path order, which is also the order the projection emits routing edges in. */
function subPath(belt: PreparedBelt, sa: number, sb: number, a: XZ, b: XZ): PathSample {
  const { pts, cum, total, closed } = belt
  const out: XZ[] = [a]
  if (!closed || sb >= sa) {
    const lo = Math.min(sa, sb)
    const hi = Math.max(sa, sb)
    const mids: XZ[] = []
    for (let i = 0; i < pts.length; i++) if (cum[i] > lo && cum[i] < hi) mids.push(pts[i])
    if (sa > sb) mids.reverse()
    out.push(...mids)
  } else {
    for (let i = 0; i < pts.length; i++) if (cum[i] > sa && cum[i] < total) out.push(pts[i])
    out.push(pts[pts.length - 1]) // the seam (same point as pts[0])
    for (let i = 0; i < pts.length; i++) if (cum[i] > 0 && cum[i] < sb) out.push(pts[i])
  }
  out.push(b)
  return makePath(out)
}

/** Build a memoised PathBetween over the placed conveyor polylines: when both endpoints project
 *  onto the same belt (within ON_BELT_TOL_M) the path follows that belt's geometry; otherwise the
 *  straight chord. Results are cached per endpoint pair (node positions are a fixed, small set). */
export function buildPathResolver(geoms: ConveyorGeomLike[]): PathBetween {
  const belts: PreparedBelt[] = []
  for (const g of geoms) {
    const wp = g.worldPath
    if (!wp || wp.length < 2) continue
    const pts = g.closed ? [...wp, wp[0]] : [...wp]
    const cum = [0]
    for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + dist(pts[i - 1], pts[i]))
    const total = cum[cum.length - 1]
    if (total > 0) belts.push({ pts, cum, total, closed: !!g.closed })
  }
  const cache = new Map<string, PathSample>()
  return (a, b) => {
    const key = `${a[0]},${a[1]}|${b[0]},${b[1]}`
    const hit = cache.get(key)
    if (hit) return hit
    let best: { belt: PreparedBelt; sa: number; sb: number; d: number } | null = null
    for (const belt of belts) {
      const pa = projectOnto(belt, a)
      const pb = projectOnto(belt, b)
      const d = Math.max(pa.d, pb.d)
      if (d <= ON_BELT_TOL_M && (!best || d < best.d)) best = { belt, sa: pa.s, sb: pb.s, d }
    }
    const path = best ? subPath(best.belt, best.sa, best.sb, a, b) : chordPath(a, b)
    cache.set(key, path)
    return path
  }
}

// ----------------------------------------------------------------------------------------------------
// Timeline — the per-tote interpolation buffer
// ----------------------------------------------------------------------------------------------------

export interface TimelinePoint {
  tMs: number
  xz: XZ
  /** Routed-to next node at this scan — the dead-reckoning target when the buffer runs dry. */
  nextXZ: XZ | null
}

export interface ToteTimeline {
  points: TimelinePoint[] // sorted by tMs ascending
}

/** Sorted insert, idempotent for re-fetched rows (same timestamp + same place just refreshes the
 *  next-node answer). Trace polls overlap heavily, so most calls are duplicates or appends. */
export function insertPoint(tl: ToteTimeline, p: TimelinePoint): void {
  const pts = tl.points
  let i = pts.length
  while (i > 0 && pts[i - 1].tMs > p.tMs) i--
  const same = (q: TimelinePoint | undefined): boolean =>
    !!q && q.tMs === p.tMs && q.xz[0] === p.xz[0] && q.xz[1] === p.xz[1]
  if (same(pts[i - 1])) {
    if (p.nextXZ) pts[i - 1].nextXZ = p.nextXZ
    return
  }
  if (same(pts[i])) {
    if (p.nextXZ) pts[i].nextXZ = p.nextXZ
    return
  }
  pts.splice(i, 0, p)
  if (pts.length > MAX_TIMELINE_POINTS) pts.splice(0, pts.length - MAX_TIMELINE_POINTS)
}

/** Drop points older than `tMs`, keeping the newest one at/before it (the bracketing anchor the
 *  delayed render clock still interpolates FROM). */
export function pruneBefore(tl: ToteTimeline, tMs: number): void {
  const pts = tl.points
  let keepFrom = 0
  for (let i = 0; i < pts.length; i++) if (pts[i].tMs <= tMs) keepFrom = i
  if (keepFrom > 0) pts.splice(0, keepFrom)
}

/** Last completed hop's speed (path metres per second), clamped to sane bounds. Per-hop speed
 *  varies with edge cost in the emulator, so the most recent hop is the best predictor. */
function estimateSpeed(tl: ToteTimeline, pathBetween: PathBetween): number {
  const pts = tl.points
  for (let i = pts.length - 1; i > 0; i--) {
    const a = pts[i - 1]
    const b = pts[i]
    const dt = (b.tMs - a.tMs) / 1000
    if (dt <= 0.05) continue
    const chord = dist(a.xz, b.xz)
    if (chord <= 0 || chord >= TELEPORT_DIST_M) continue
    const v = pathBetween(a.xz, b.xz).total / dt
    if (v >= MIN_SPEED_MPS && v <= MAX_SPEED_MPS) return v
  }
  return FALLBACK_SPEED_MPS
}

/** Map travelled distance to an eased one that decelerates smoothly to a stop exactly at `total`
 *  (velocity is continuous: 1 at the ease-zone boundary, 0 at arrival). Used by dead reckoning so
 *  a tote glides to rest at the known next node instead of hitting a wall. */
function easedDistance(d: number, total: number): number {
  if (total <= 0) return 0
  const z = Math.min(EASE_OUT_M, total / 2)
  const lin = total - z
  if (d <= lin) return d
  const u = Math.min(2, (d - lin) / z)
  return lin + z * (u - (u * u) / 4)
}

/**
 * Position at render time `tMs`:
 *  - between two buffered points → interpolate along the conveyor geometry by time fraction
 *    (which exactly reproduces the per-hop speed the floor reported),
 *  - across a teleport-sized or long-dwell gap → hold at the earlier point, then jump (real),
 *  - past the newest point (buffer underrun) → dead-reckon toward the known next node at the last
 *    observed speed, easing to a stop on arrival,
 *  - before the oldest point → hold there.
 * Returns null only for an empty timeline.
 */
export function sampleTimeline(tl: ToteTimeline, tMs: number, pathBetween: PathBetween): XZ | null {
  const pts = tl.points
  if (!pts.length) return null
  if (tMs <= pts[0].tMs) return pts[0].xz
  const last = pts[pts.length - 1]
  if (tMs >= last.tMs) {
    if (!last.nextXZ) return last.xz
    const chord = dist(last.xz, last.nextXZ)
    if (chord <= 0 || chord >= TELEPORT_DIST_M) return last.xz // never reckon across a teleport
    const path = pathBetween(last.xz, last.nextXZ)
    const travelled = ((tMs - last.tMs) / 1000) * estimateSpeed(tl, pathBetween)
    return pointAtLen(path, easedDistance(travelled, path.total))
  }
  let i = pts.length - 2
  while (i > 0 && pts[i].tMs > tMs) i--
  const a = pts[i]
  const b = pts[i + 1]
  const dt = b.tMs - a.tMs
  if (dt <= 0) return b.xz
  const chord = dist(a.xz, b.xz)
  if (chord >= TELEPORT_DIST_M || dt > MAX_SEG_GAP_MS) return a.xz // dwell/teleport: hold, then jump
  const path = pathBetween(a.xz, b.xz)
  return pointAtLen(path, ((tMs - a.tMs) / dt) * path.total)
}

// ----------------------------------------------------------------------------------------------------
// Per-frame smoothing — absorb data revisions instead of snapping
// ----------------------------------------------------------------------------------------------------

/** Exponential decay constant for the revision-error offset; ~95% of a revision is blended away
 *  within ~0.5 s. */
const ERR_DECAY_TAU_S = 0.17

/** Target movement beyond plausible one-frame belt motion (plus this slack) counts as a data
 *  revision rather than continuous motion. */
const FRAME_JUMP_SLACK_M = 0.25

export interface SmoothState {
  lastTarget: XZ | null
  err: XZ
}

export function newSmoothState(): SmoothState {
  return { lastTarget: null, err: [0, 0] }
}

/**
 * One smoothing step: follow the (mostly continuous) sampled target; when fresh data REVISES the
 * target discontinuously, capture the offset so the rendered position stays put, then decay it
 * away over ~0.5 s. Jumps of TELEPORT_DIST_M or more snap — they are genuine discontinuities.
 * Mutates `state`; returns the rendered position.
 */
export function smoothStep(state: SmoothState, target: XZ, deltaS: number): XZ {
  const prev = state.lastTarget
  if (prev) {
    const jump = dist(prev, target)
    if (jump >= TELEPORT_DIST_M) {
      state.err = [0, 0]
    } else if (jump > MAX_SPEED_MPS * deltaS + FRAME_JUMP_SLACK_M) {
      // Rendered position before this frame was prev + err; keep it identical across the revision.
      state.err = [prev[0] + state.err[0] - target[0], prev[1] + state.err[1] - target[1]]
    }
  }
  const k = Math.exp(-Math.max(0, deltaS) / ERR_DECAY_TAU_S)
  state.err = [state.err[0] * k, state.err[1] * k]
  state.lastTarget = target
  return [target[0] + state.err[0], target[1] + state.err[1]]
}
