// Hardware-visualisation simulator for the public live demo (VITE_DEMO builds only).
//
// The Hardware visualisation screen (hardwaretwin/) renders a live 3D twin from data it already
// polls: the automation TOPOLOGY (geometry, loaded once) plus the flow device-task feed and the
// twin read-models (tote paths / AMR fleet / ASRS cranes). The real backend resolves all of that
// from a running emulator; the demo has no backend, so this module fabricates the SAME shapes from
// the wall clock — a fixed warehouse layout (a recirculating conveyor loop around an ASRS rack, a
// sorter and two pick stations) with a handful of totes gliding round the loop, robots roaming the
// aisles and a crane working the rack. Everything is a pure function of `nowMs`, so each mock poll
// returns a fresh, consistent picture and the totes appear to drive around in real time.
//
// Geometry lives here ONCE: the topology returned to the screen and the motion fed to it share the
// same LOOP polyline, so a tote's path can never drift off the drawn belt.

import type { DeviceTask } from '../transport/api'
import type {
  Amr,
  AmrFleet,
  AsrsCrane,
  AsrsCranes,
  TwinPaths,
  TwinTotePath,
  TwinWaypoint,
} from '../hardwaretwin/api'
import type { Equipment } from '../masterdata/api'
import type {
  AutomationConnection,
  AutomationEquipment,
  AutomationFunctionPoint,
  AutomationLevel,
  AutomationTopology,
} from '../topology/automationApi'

// The demo warehouse (matches the fixtures' DEMO DC) and the two real GTP station ids the pick
// stations link to (so a placed workstation references the same station the GTP fixtures use).
const WAREHOUSE_ID = '394dfd10-f541-48b5-84d8-773322549a3b'
const PP1_STATION_ID = '407fced6-5f56-4b56-8b3a-2641cfa5d027'
const PP2_STATION_ID = 'b1d2c3e4-aaaa-4bbb-8ccc-000000000001'

type XZ = [number, number]

// ----------------------------------------------------------------------------------------------------
// Layout — one recirculating loop, an ASRS rack inside it, a sorter on the bottom run, two stations.
// ----------------------------------------------------------------------------------------------------

// Chamfered-rectangle racetrack (world XZ metres). Drawn closed, so the last point links back to the
// first. The totes ride exactly these segments; the same polyline is the conveyor placement's `path`.
const LOOP: XZ[] = [
  [-10, -7],
  [10, -7],
  [12, -5],
  [12, 5],
  [10, 7],
  [-10, 7],
  [-12, 5],
  [-12, -5],
]

// Cumulative arc length of the CLOSED loop (the closing segment back to LOOP[0] is included), so a
// position can be resolved from a distance travelled and totes wrap seamlessly past the seam.
const LOOP_CUM: number[] = (() => {
  const cum = [0]
  for (let i = 1; i < LOOP.length; i++) cum.push(cum[i - 1] + dist(LOOP[i - 1], LOOP[i]))
  cum.push(cum[cum.length - 1] + dist(LOOP[LOOP.length - 1], LOOP[0])) // seam
  return cum
})()
const LOOP_LEN = LOOP_CUM[LOOP_CUM.length - 1]

function dist(a: XZ, b: XZ): number {
  return Math.hypot(b[0] - a[0], b[1] - a[1])
}

/** World point at arc length `s` (wrapped into [0, LOOP_LEN)) along the closed loop. */
function pointOnLoop(s: number): XZ {
  const target = ((s % LOOP_LEN) + LOOP_LEN) % LOOP_LEN
  let i = 1
  while (i < LOOP_CUM.length && LOOP_CUM[i] < target) i++
  const a = LOOP[i - 1]
  const b = LOOP[i % LOOP.length] // the seam segment ends back at LOOP[0]
  const seg = LOOP_CUM[i] - LOOP_CUM[i - 1] || 1
  const f = (target - LOOP_CUM[i - 1]) / seg
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f]
}

// ----------------------------------------------------------------------------------------------------
// Static master-data: the equipment library the topology placements classify against. The twin's
// renderer keys conveyor/asrs/sorter looks off the library family/type (AutomationTopology3D), so
// these MUST be present for the loop to draw as a belt, the rack as racking and the sorter as a sorter.
// ----------------------------------------------------------------------------------------------------

const EQ_CONVEYOR = 'demo-eq-conveyor'
const EQ_ASRS = 'demo-eq-asrs'
const EQ_SORTER = 'demo-eq-sorter'

export const EQUIPMENT_LIBRARY: Equipment[] = [
  { id: EQ_CONVEYOR, warehouseId: WAREHOUSE_ID, family: 'CONVEYOR', type: 'ROLLER_CONVEYOR', status: 'ACTIVE' },
  { id: EQ_ASRS, warehouseId: WAREHOUSE_ID, family: 'ASRS', type: 'ASRS', status: 'ACTIVE' },
  { id: EQ_SORTER, warehouseId: WAREHOUSE_ID, family: 'SORTER', type: 'SORTER', status: 'ACTIVE' },
]

// ----------------------------------------------------------------------------------------------------
// Static topology: one level carrying the loop, the rack, the sorter and the two pick stations.
// ----------------------------------------------------------------------------------------------------

const LEVEL: AutomationLevel = {
  id: 'demo-level-0',
  number: 1,
  name: 'Ground floor',
  elevationM: 0,
  status: 'ACTIVE',
}

const LOOP_PLACED_ID = 'demo-conv-loop'

const EQUIPMENT: AutomationEquipment[] = [
  {
    id: LOOP_PLACED_ID,
    levelId: LEVEL.id,
    equipmentId: EQ_CONVEYOR,
    code: 'LOOP-1',
    posXM: 0,
    posYM: 0,
    posZM: 0,
    rotationDeg: 0,
    tiltDeg: 0,
    lengthM: 1,
    widthM: 0.8,
    heightM: 1,
    status: 'ACTIVE',
    path: LOOP.map((p) => [p[0], p[1]]),
    sections: null,
    closed: true,
    category: 'conveyor',
  },
  {
    id: 'demo-asrs-1',
    levelId: LEVEL.id,
    equipmentId: EQ_ASRS,
    code: 'ASRS-1',
    posXM: 0,
    posYM: 0,
    posZM: 0,
    rotationDeg: 0,
    tiltDeg: 0,
    lengthM: 14,
    widthM: 2.4,
    heightM: 4,
    status: 'ACTIVE',
    category: 'asrs',
  },
  {
    id: 'demo-sorter-1',
    levelId: LEVEL.id,
    equipmentId: EQ_SORTER,
    code: 'SORT-1',
    posXM: 0,
    posYM: 0,
    posZM: -7,
    rotationDeg: 0,
    tiltDeg: 0,
    lengthM: 2.4,
    widthM: 1.4,
    heightM: 1,
    status: 'ACTIVE',
    category: 'sorter',
  },
  {
    id: 'demo-ws-pp1',
    levelId: LEVEL.id,
    code: 'PP1',
    stationId: PP1_STATION_ID,
    posXM: -5,
    posYM: 0,
    posZM: 9,
    rotationDeg: 0,
    tiltDeg: 0,
    lengthM: 1.6,
    widthM: 1.6,
    heightM: 1,
    status: 'ACTIVE',
    category: 'workstation',
  },
  {
    id: 'demo-ws-pp2',
    levelId: LEVEL.id,
    code: 'PP2',
    stationId: PP2_STATION_ID,
    posXM: 5,
    posYM: 0,
    posZM: 9,
    rotationDeg: 0,
    tiltDeg: 0,
    lengthM: 1.6,
    widthM: 1.6,
    heightM: 1,
    status: 'ACTIVE',
    category: 'workstation',
  },
]

const CONNECTIONS: AutomationConnection[] = [
  { id: 'demo-conn-pp1', fromPlacedId: LOOP_PLACED_ID, toPlacedId: 'demo-ws-pp1', fromPathIndex: 5, status: 'ACTIVE' },
  { id: 'demo-conn-pp2', fromPlacedId: LOOP_PLACED_ID, toPlacedId: 'demo-ws-pp2', fromPathIndex: 4, status: 'ACTIVE' },
]

// SCAN/DIVERT markers along the loop (offsetM = arc length from the path start) — pure visual flavour.
const FUNCTION_POINTS: AutomationFunctionPoint[] = [
  { id: 'demo-fp-scan-in', placedId: LOOP_PLACED_ID, functionType: 'SCAN', name: 'Induct scan', offsetM: 4, status: 'ACTIVE' },
  { id: 'demo-fp-div', placedId: LOOP_PLACED_ID, functionType: 'DIVERT_RIGHT', name: 'Station divert', offsetM: LOOP_CUM[5], status: 'ACTIVE' },
  { id: 'demo-fp-scan-out', placedId: LOOP_PLACED_ID, functionType: 'SCAN', name: 'Sorter scan', offsetM: LOOP_CUM[1] + 2, status: 'ACTIVE' },
]

export const AUTOMATION_TOPOLOGY: AutomationTopology = {
  levels: [LEVEL],
  equipment: EQUIPMENT,
  connections: CONNECTIONS,
  functionPoints: FUNCTION_POINTS,
}

// ----------------------------------------------------------------------------------------------------
// Live motion — all derived from `nowMs` so each poll advances the picture.
// ----------------------------------------------------------------------------------------------------

const TOTE_COUNT = 9
const TOTE_SPEED_MPS = 0.6
// Totes whose index hits this cadence carry a recirculation flag (rendered amber) for variety.
const RECIRC_EVERY = 4

/** Arc position of tote `i` at `tMs` — evenly spaced around the loop, all moving forward. */
function toteArc(i: number, tMs: number): number {
  const offset = (i / TOTE_COUNT) * LOOP_LEN
  return TOTE_SPEED_MPS * (tMs / 1000) + offset
}

function toteHuId(i: number): string {
  return `demo-tote-${i}`
}
function toteHuCode(i: number): string {
  return `TOTE-${String(i + 1).padStart(3, '0')}`
}
function isRecirc(i: number): boolean {
  return i % RECIRC_EVERY === RECIRC_EVERY - 1
}

// Stable-ish synthetic id from a string seed (no Math.random — keeps polls deterministic).
function seededId(seed: string): string {
  let h = 0
  for (let k = 0; k < seed.length; k++) h = (h * 31 + seed.charCodeAt(k)) | 0
  const hex = (h >>> 0).toString(16).padStart(8, '0')
  return `demo${hex}-0000-4000-8000-${hex}00000000`.slice(0, 36)
}

/** Device-task feed: a RUNNING CONVEY task per circulating tote (so the twin renders it in transit),
 *  a couple of RUNNING ASRS tasks (rack activity pip) and recent COMPLETED tasks (a live throughput
 *  number). Honest enough to drive the twin without any backend. */
export function deviceTasks(nowMs: number): DeviceTask[] {
  const tasks: DeviceTask[] = []

  for (let i = 0; i < TOTE_COUNT; i++) {
    tasks.push({
      id: seededId(`task-${i}`),
      warehouseId: WAREHOUSE_ID,
      family: 'CONVEYOR',
      equipmentId: null,
      command: 'CONVEY',
      payload: { huId: toteHuId(i), huCode: toteHuCode(i) },
      correlationId: toteHuId(i),
      status: 'IN_PROGRESS',
      detail: 'conveyor walking CONVEY around the loop',
      result: { family: 'CONVEYOR', walked: true, recirculations: isRecirc(i) ? 1 : 0 },
      actor: 'system',
      createdAt: new Date(nowMs - 25_000).toISOString(),
    })
  }

  // Two ASRS cycles in flight — lights the rack (family fallback) and explains the crane motion. No
  // huId/correlationId, so they drive equipment activity only and never count as in-transit totes.
  for (let i = 0; i < 2; i++) {
    tasks.push({
      id: seededId(`asrs-${i}`),
      warehouseId: WAREHOUSE_ID,
      family: 'ASRS',
      equipmentId: null,
      command: i === 0 ? 'RETRIEVE' : 'STORE',
      payload: { huCode: `ASRS-HU-${i}` },
      correlationId: null,
      status: 'IN_PROGRESS',
      detail: 'asrs simulated cycle',
      result: { family: 'ASRS' },
      actor: 'system',
      createdAt: new Date(nowMs - 8_000).toISOString(),
    })
  }

  // A trail of recently COMPLETED conveys so the throughput-per-minute chip reads a lively number
  // (deriveTwin counts COMPLETED tasks created in the last 60 s).
  for (let k = 0; k < 14; k++) {
    tasks.push({
      id: seededId(`done-${k}`),
      warehouseId: WAREHOUSE_ID,
      family: 'CONVEYOR',
      equipmentId: null,
      command: 'CONVEY',
      payload: { huCode: `DONE-${k}` },
      correlationId: seededId(`done-${k}`),
      status: 'COMPLETED',
      detail: 'conveyor walked CONVEY to station',
      result: { family: 'CONVEYOR', walked: true },
      actor: 'system',
      createdAt: new Date(nowMs - (4_000 + k * 4_000)).toISOString(),
    })
  }

  // Newest-first, matching the real feed's ordering.
  tasks.sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
  return tasks
}

// How much history each poll hands the twin's interpolation buffer, and how finely. The render clock
// runs ~5 s behind real time (motion.ts RENDER_DELAY_MS), so a window comfortably past that with
// closely-spaced points keeps motion pure interpolation between known points — smooth, never stalling.
const PATH_WINDOW_MS = 18_000
const PATH_STEP_MS = 1_500

/** Every circulating tote's resolved conveyor polyline (positions baked in, server-stamped times)
 *  plus the server clock — the twin replays this exactly like the real "visu master" read model. */
export function totePaths(nowMs: number): TwinPaths {
  const totes: TwinTotePath[] = []
  for (let i = 0; i < TOTE_COUNT; i++) {
    const waypoints: TwinWaypoint[] = []
    for (let back = PATH_WINDOW_MS; back >= 0; back -= PATH_STEP_MS) {
      const tMs = nowMs - back
      const [x, z] = pointOnLoop(toteArc(i, tMs))
      waypoints.push({ code: `${LOOP_PLACED_ID}@${i}`, x, z, tMs })
    }
    const tNext = nowMs + PATH_STEP_MS
    const [nx, nz] = pointOnLoop(toteArc(i, tNext))
    totes.push({
      huId: toteHuId(i),
      huCode: toteHuCode(i),
      state: isRecirc(i) ? 'recirculating' : 'in-transit',
      waypoints,
      next: { code: `${LOOP_PLACED_ID}@${i}`, x: nx, z: nz, tMs: null },
    })
  }
  return { serverNowMs: nowMs, totes }
}

/** Three AMRs roaming the aisles outside the loop — each circles a fixed point, so the twin's eased
 *  per-poll motion glides them smoothly. */
export function amrFleet(nowMs: number): AmrFleet {
  const centres: XZ[] = [
    [-16, 0],
    [16, 0],
    [0, 13],
  ]
  const amrs: Amr[] = centres.map((c, i) => {
    // ~0.13 rad/s over a 3 m circle ≈ 0.4 m/s — a believable robot trundle, smooth under the poll ease.
    const ang = nowMs * 0.00013 + (i * 2 * Math.PI) / centres.length
    return {
      amrId: `demo-amr-${i + 1}`,
      x: c[0] + Math.cos(ang) * 3,
      z: c[1] + Math.sin(ang) * 3,
      status: 'moving',
      huCode: i === 0 ? 'AMR-HU-01' : null,
    }
  })
  return { serverTimeMs: nowMs, amrs }
}

/** One ASRS crane working the rack: it tracks along the aisle and lifts its carriage up and down. */
export function asrsCranes(nowMs: number): AsrsCranes {
  const crane: AsrsCrane = {
    craneId: 'demo-crane-1',
    x: Math.sin(nowMs * 0.00018) * 6,
    y: 1.8 + Math.sin(nowMs * 0.00035) * 1.4,
    z: 0,
    status: 'moving',
    huCode: 'ASRS-HU-0',
  }
  return { serverTimeMs: nowMs, cranes: [crane] }
}
