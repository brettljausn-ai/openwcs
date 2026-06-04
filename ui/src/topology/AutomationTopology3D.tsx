import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Canvas, type ThreeEvent } from '@react-three/fiber'
import { Grid, Html, Line, OrbitControls, PivotControls, Text } from '@react-three/drei'
import * as THREE from 'three'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useWarehouse } from '../warehouse/WarehouseContext'
import {
  listEquipment,
  listStorageBlocks,
  updateStorageBlock,
  type Equipment,
  type StorageBlock,
} from '../masterdata/api'
import {
  loadAutomationTopology,
  saveAutomationTopology,
  type AutomationConnection,
  type AutomationEquipment,
  type AutomationFunctionPoint,
  type AutomationLevel,
} from './automationApi'

const DEG = Math.PI / 180

// The process types a function point can carry. Used as the default Select options when the
// placed equipment's library entry doesn't declare its own `processTypes`.
const FUNCTION_TYPES = [
  'SCAN',
  'LABEL_APPLICATOR',
  'DIVERT_LEFT',
  'DIVERT_RIGHT',
  'DWS',
  'QUERY_POINT',
  'WRAPPER',
  'INDUCT',
  'DISCHARGE',
] as const

// Short marker labels per function type for the 3D <Html> tag. Falls back to the raw type.
const FUNCTION_SHORT: Record<string, string> = {
  SCAN: 'SCAN',
  LABEL_APPLICATOR: 'LBL',
  DIVERT_LEFT: '◀ DIV',
  DIVERT_RIGHT: 'DIV ▶',
  DWS: 'DWS',
  QUERY_POINT: 'QRY',
  WRAPPER: 'WRAP',
  INDUCT: 'IN',
  DISCHARGE: 'OUT',
}

// Distinct marker colour per function type, so points read at a glance in the scene.
function functionColor(type: string): string {
  switch (type) {
    case 'SCAN':
      return '#5ec8e0'
    case 'LABEL_APPLICATOR':
      return '#e0c45e'
    case 'DIVERT_LEFT':
    case 'DIVERT_RIGHT':
      return '#e07a5e'
    case 'DWS':
      return '#b65ee0'
    case 'QUERY_POINT':
      return '#5ee08a'
    case 'WRAPPER':
      return '#e05e9c'
    case 'INDUCT':
      return '#8DC63F'
    case 'DISCHARGE':
      return '#f0a85a'
    default:
      return '#cfd8d2'
  }
}

// True when a placed item's library entry marks it as an ASRS-style storage system that a
// storage block can be bound to (so we offer the storage-area linking panel).
function isAsrs(eq: AutomationEquipment, lib: Map<string, Equipment>): boolean {
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  const family = (meta?.family ?? '').toUpperCase()
  const type = (meta?.type ?? '').toUpperCase()
  return family === 'ASRS' || family === 'AUTOSTORE' || family === 'AMR' || type === 'ASRS'
}

// Position of a point at `offsetM` along an equipment, returned in level-local world XZ plus the
// unit direction (dx,dz) of travel at that offset (used to push the marker to a side). For a
// polyline conveyor this walks the path by arc-length; for a box it projects along the box length
// from its start endpoint (posX/posZ − length/2 rotated by yaw).
function pointAlong(
  eq: AutomationEquipment,
  offsetM: number,
): { x: number; z: number; dx: number; dz: number } {
  if (Array.isArray(eq.path) && eq.path.length >= 2) {
    const path = eq.path
    const pairCount = eq.closed ? path.length : path.length - 1
    let remaining = Math.max(0, offsetM)
    let last = { x: path[0][0], z: path[0][1], dx: 1, dz: 0 }
    for (let i = 0; i < pairCount; i++) {
      const a = path[i]
      const b = path[(i + 1) % path.length]
      const dx = b[0] - a[0]
      const dz = b[1] - a[1]
      const segLen = Math.hypot(dx, dz)
      if (segLen < 1e-6) continue
      const ux = dx / segLen
      const uz = dz / segLen
      if (remaining <= segLen) {
        return { x: a[0] + ux * remaining, z: a[1] + uz * remaining, dx: ux, dz: uz }
      }
      remaining -= segLen
      last = { x: b[0], z: b[1], dx: ux, dz: uz }
    }
    return last
  }
  // Straight box: start endpoint is posX/posZ − (length/2) along yaw; walk forward by offset.
  const yaw = eq.rotationDeg * DEG
  const ux = Math.cos(yaw)
  const uz = Math.sin(yaw)
  const startX = eq.posXM - (ux * eq.lengthM) / 2
  const startZ = eq.posZM - (uz * eq.lengthM) / 2
  const t = Math.min(Math.max(offsetM, 0), eq.lengthM)
  return { x: startX + ux * t, z: startZ + uz * t, dx: ux, dz: uz }
}

// Three muted, distinct colours keyed off the equipment family/type. Anything that isn't a
// recognisable conveyor / storage(ASRS) / sorter falls back to a neutral slate.
function colorFor(eq: AutomationEquipment, lib: Map<string, Equipment>): string {
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  const key = `${meta?.family ?? ''} ${meta?.type ?? ''} ${meta?.subtype ?? ''} ${eq.code}`.toLowerCase()
  if (/conveyor|roller|belt|transport/.test(key)) return '#4f8a8b' // teal — transport
  if (/asrs|storage|shuttle|rack|crane|stacker|autostore/.test(key)) return '#7a6cc0' // violet — storage
  if (/sort|divert|merge|switch/.test(key)) return '#c08a4f' // amber — sortation
  return '#6b7a85' // slate — other
}

function equipmentTypeLabel(meta?: Equipment): string {
  if (!meta) return 'Equipment'
  return meta.type || meta.subtype || meta.family || 'Equipment'
}

// A placement is a conveyor (polyline-capable) when its library family is CONVEYOR. That family
// covers straight conveyors, curves, and sorters. Everything else stays a single box.
function isConveyor(eq: AutomationEquipment, lib: Map<string, Equipment>): boolean {
  const meta = eq.equipmentId ? lib.get(eq.equipmentId) : undefined
  return (meta?.family ?? '').toUpperCase() === 'CONVEYOR'
}

// A usable polyline needs at least two waypoints.
function hasPath(eq: AutomationEquipment): boolean {
  return Array.isArray(eq.path) && eq.path.length >= 2
}

function num(v: string, fallback = 0): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
}

// World-space centre of a placed item, including its level's elevation so cross-level links
// render at the right height. For a polyline conveyor the centre is the mean of its waypoints.
function worldCenter(
  eq: AutomationEquipment,
  levels: AutomationLevel[],
): [number, number, number] {
  const elev = levels.find((l) => l.id === eq.levelId)?.elevationM ?? 0
  if (Array.isArray(eq.path) && eq.path.length >= 1) {
    let sx = 0
    let sz = 0
    for (const p of eq.path) {
      sx += p[0]
      sz += p[1]
    }
    const n = eq.path.length
    return [sx / n, elev + eq.heightM / 2 + eq.posYM, sz / n]
  }
  return [eq.posXM, elev + eq.heightM / 2 + eq.posYM, eq.posZM]
}

export default function AutomationTopology3D() {
  const { currentWarehouseId: warehouseId } = useWarehouse()

  const [levels, setLevels] = useState<AutomationLevel[]>([])
  const [equipment, setEquipment] = useState<AutomationEquipment[]>([])
  // Connections + function points are not edited in this slice; we hold them so Save round-trips them.
  const [connections, setConnections] = useState<AutomationConnection[]>([])
  const [functionPoints, setFunctionPoints] = useState<AutomationFunctionPoint[]>([])

  const [library, setLibrary] = useState<Equipment[]>([])
  const [activeLevelId, setActiveLevelId] = useState<string>('')
  const [selectedId, setSelectedId] = useState<string | null>(null)

  // When ON, clicking the ground plane appends a waypoint to the selected conveyor's path.
  const [drawPath, setDrawPath] = useState(false)
  // While a waypoint handle is being dragged we disable OrbitControls so the camera stays put.
  const [orbitEnabled, setOrbitEnabled] = useState(true)

  // Connect mode: while ON, equipment clicks pick a connection source then target instead of
  // selecting/dragging. connectFrom holds the chosen source's placed id (null = pick source next).
  const [connectMode, setConnectMode] = useState(false)
  const [connectFrom, setConnectFrom] = useState<string | null>(null)
  const [selectedConnId, setSelectedConnId] = useState<string | null>(null)
  // Library filter: show only equipment with no placement anywhere in the editor.
  const [unplacedOnly, setUnplacedOnly] = useState(false)

  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)

  // Monotonic counter for default codes within a session, so two quick adds don't collide.
  const counter = useRef(0)

  const libById = useMemo(() => {
    const m = new Map<string, Equipment>()
    for (const e of library) if (e.id) m.set(e.id, e)
    return m
  }, [library])

  // How many placements (across all levels) reference each master-data equipment id. Drives the
  // library's placed/unplaced badges and the "Unplaced only" filter; recomputes as equipment changes.
  const placementCounts = useMemo(() => {
    const m = new Map<string, number>()
    for (const e of equipment) {
      if (!e.equipmentId) continue
      m.set(e.equipmentId, (m.get(e.equipmentId) ?? 0) + 1)
    }
    return m
  }, [equipment])

  const load = useCallback(async () => {
    if (!warehouseId) {
      setLevels([])
      setEquipment([])
      setConnections([])
      setFunctionPoints([])
      setActiveLevelId('')
      setError(null)
      setInfo(null)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const [topo, lib] = await Promise.all([
        loadAutomationTopology(warehouseId),
        listEquipment(warehouseId).catch(() => [] as Equipment[]),
      ])
      setLevels(topo.levels)
      setEquipment(topo.equipment)
      setConnections(topo.connections)
      setFunctionPoints(topo.functionPoints)
      setLibrary(lib)
      setActiveLevelId((prev) =>
        topo.levels.some((l) => l.id === prev) ? prev : topo.levels[0]?.id ?? '',
      )
      setSelectedId(null)
      setDirty(false)
      setInfo(`Loaded ${topo.levels.length} level(s), ${topo.equipment.length} equipment`)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  useEffect(() => {
    load()
  }, [load])

  const save = useCallback(async () => {
    if (!warehouseId) {
      setError('No active warehouse selected')
      return
    }
    setSaving(true)
    setError(null)
    try {
      const saved = await saveAutomationTopology(warehouseId, {
        levels,
        equipment,
        connections,
        functionPoints,
      })
      setLevels(saved.levels)
      setEquipment(saved.equipment)
      setConnections(saved.connections)
      setFunctionPoints(saved.functionPoints)
      setActiveLevelId((prev) =>
        saved.levels.some((l) => l.id === prev) ? prev : saved.levels[0]?.id ?? '',
      )
      setSelectedId(null)
      setDirty(false)
      setInfo('Saved')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }, [warehouseId, levels, equipment, connections, functionPoints])

  // ---- level helpers -----------------------------------------------------
  const activeLevel = useMemo(
    () => levels.find((l) => l.id === activeLevelId) ?? null,
    [levels, activeLevelId],
  )

  const addLevel = useCallback(() => {
    const maxNumber = levels.reduce((m, l) => Math.max(m, l.number), 0)
    const number = maxNumber + 1
    const level: AutomationLevel = {
      id: crypto.randomUUID(),
      number,
      name: `Level ${number}`,
      elevationM: number * 5,
      status: 'ACTIVE',
    }
    setLevels((ls) => [...ls, level])
    setActiveLevelId(level.id)
    setDirty(true)
  }, [levels])

  const patchActiveLevel = useCallback(
    (patch: Partial<AutomationLevel>) => {
      if (!activeLevelId) return
      setLevels((ls) => ls.map((l) => (l.id === activeLevelId ? { ...l, ...patch } : l)))
      setDirty(true)
    },
    [activeLevelId],
  )

  // ---- equipment helpers -------------------------------------------------
  const levelEquipment = useMemo(
    () => equipment.filter((e) => e.levelId === activeLevelId),
    [equipment, activeLevelId],
  )

  const selected = useMemo(
    () => equipment.find((e) => e.id === selectedId) ?? null,
    [equipment, selectedId],
  )

  const selectedIsConveyor = selected ? isConveyor(selected, libById) : false
  const selectedMeta = selected?.equipmentId ? libById.get(selected.equipmentId) : undefined
  const selectedIsAsrs = selected ? isAsrs(selected, libById) : false

  // Function points belonging to the currently-selected placed equipment.
  const selectedFunctionPoints = useMemo(
    () => (selected ? functionPoints.filter((f) => f.placedId === selected.id) : []),
    [functionPoints, selected],
  )

  // Draw mode only makes sense for a selected conveyor — drop it otherwise.
  useEffect(() => {
    if (!selected || !selectedIsConveyor) setDrawPath(false)
  }, [selected, selectedIsConveyor])

  const patchEquipment = useCallback((id: string, patch: Partial<AutomationEquipment>) => {
    setEquipment((es) => es.map((e) => (e.id === id ? { ...e, ...patch } : e)))
    setDirty(true)
  }, [])

  // ---- function-point helpers --------------------------------------------
  const addFunctionPoint = useCallback((fp: AutomationFunctionPoint) => {
    setFunctionPoints((fps) => [...fps, fp])
    setDirty(true)
  }, [])

  const deleteFunctionPoint = useCallback((id: string) => {
    setFunctionPoints((fps) => fps.filter((f) => f.id !== id))
    setDirty(true)
  }, [])

  // Append a waypoint to an equipment's path (creating one if needed). Used by draw mode.
  const appendWaypoint = useCallback((id: string, x: number, z: number) => {
    setEquipment((es) =>
      es.map((e) => {
        if (e.id !== id) return e
        const prev = Array.isArray(e.path) ? e.path : []
        return { ...e, path: [...prev, [+x.toFixed(3), +z.toFixed(3)]] }
      }),
    )
    setDirty(true)
  }, [])

  // Move a single waypoint (live, during a handle drag).
  const moveWaypoint = useCallback((id: string, index: number, x: number, z: number) => {
    setEquipment((es) =>
      es.map((e) => {
        if (e.id !== id || !Array.isArray(e.path) || index < 0 || index >= e.path.length) return e
        const next = e.path.map((p, i) => (i === index ? [+x.toFixed(3), +z.toFixed(3)] : p))
        return { ...e, path: next }
      }),
    )
    setDirty(true)
  }, [])

  const addFromLibrary = useCallback(
    (meta: Equipment) => {
      if (!activeLevelId) {
        setError('Add a level first')
        return
      }
      counter.current += 1
      const typeLabel = equipmentTypeLabel(meta)
      const placed: AutomationEquipment = {
        id: crypto.randomUUID(),
        levelId: activeLevelId,
        equipmentId: meta.id ?? null,
        code: `${typeLabel}-${counter.current}`,
        posXM: 0,
        posYM: 0,
        posZM: 0,
        rotationDeg: 0,
        tiltDeg: 0,
        lengthM: meta.defaultLengthM ?? 1.0,
        widthM: meta.defaultWidthM ?? 1.0,
        heightM: meta.defaultHeightM ?? 1.0,
        status: 'ACTIVE',
      }
      setEquipment((es) => [...es, placed])
      setSelectedId(placed.id)
      setDirty(true)
    },
    [activeLevelId],
  )

  const deleteSelected = useCallback(() => {
    if (!selectedId) return
    setEquipment((es) => es.filter((e) => e.id !== selectedId))
    // Drop any connections that referenced the removed item so we don't keep dangling links.
    setConnections((cs) =>
      cs.filter((c) => c.fromPlacedId !== selectedId && c.toPlacedId !== selectedId),
    )
    // Drop function points that lived on the removed item.
    setFunctionPoints((fps) => fps.filter((f) => f.placedId !== selectedId))
    if (connectFrom === selectedId) setConnectFrom(null)
    setSelectedId(null)
    setDirty(true)
  }, [selectedId, connectFrom])

  // ---- connection helpers ------------------------------------------------
  // A click on a piece of equipment while connect mode is on. First pick = source; second pick =
  // target (creates the connection). Clicking the same item cancels the in-progress pick.
  const handleConnectPick = useCallback(
    (placedId: string) => {
      setConnectFrom((from) => {
        if (!from) return placedId
        if (from === placedId) return null // clicking the source again cancels
        setConnections((cs) => [
          ...cs,
          {
            id: crypto.randomUUID(),
            fromPlacedId: from,
            toPlacedId: placedId,
            fromPointId: null,
            toPointId: null,
            label: null,
            status: 'ACTIVE',
          },
        ])
        setDirty(true)
        return null // ready to chain another from-pick
      })
    },
    [],
  )

  const deleteConnection = useCallback((id: string) => {
    setConnections((cs) => cs.filter((c) => c.id !== id))
    setSelectedConnId((s) => (s === id ? null : s))
    setDirty(true)
  }, [])

  const toggleConnectMode = useCallback(() => {
    setConnectMode((on) => {
      const next = !on
      setConnectFrom(null)
      if (next) {
        setSelectedId(null)
        setDrawPath(false)
      }
      return next
    })
  }, [])

  // Lookup of placed item (any level) by id, for resolving connection endpoints to codes.
  const equipmentById = useMemo(() => {
    const m = new Map<string, AutomationEquipment>()
    for (const e of equipment) m.set(e.id, e)
    return m
  }, [equipment])

  // Group the equipment library by type for the left panel. "Unplaced only" hides any library
  // entry that already has at least one placement in the editor.
  const libraryGroups = useMemo(() => {
    const groups = new Map<string, Equipment[]>()
    for (const e of library) {
      if (unplacedOnly && e.id && (placementCounts.get(e.id) ?? 0) > 0) continue
      const key = equipmentTypeLabel(e)
      const arr = groups.get(key) ?? []
      arr.push(e)
      groups.set(key, arr)
    }
    return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [library, unplacedOnly, placementCounts])

  if (!warehouseId) {
    return (
      <div className="alert alert-danger" style={{ margin: '1rem 0' }}>
        No active warehouse selected — pick one in the top-bar switcher.
      </div>
    )
  }

  return (
    <div className="atopo">
      {/* ---- top toolbar: level tabs + actions ---- */}
      <div className="atopo-toolbar">
        <div className="atopo-levels">
          {levels.map((l) => (
            <button
              key={l.id}
              type="button"
              className={`atopo-leveltab${l.id === activeLevelId ? ' is-active' : ''}`}
              onClick={() => {
                setActiveLevelId(l.id)
                setSelectedId(null)
              }}
              title={`Elevation ${l.elevationM} m`}
            >
              {l.number} · {l.name}
            </button>
          ))}
          <button type="button" className="btn btn-ghost btn-sm" onClick={addLevel}>
            + Add level
          </button>
        </div>
        <div className="atopo-actions">
          {dirty && <span className="atopo-dirty">Unsaved changes</span>}
          <button
            type="button"
            className={`btn btn-sm ${connectMode ? 'btn-primary' : 'btn-ghost'}`}
            onClick={toggleConnectMode}
            disabled={loading || saving}
            title="Link two pieces of equipment: click a source, then a target."
          >
            {connectMode ? 'Connecting…' : 'Connect'}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={load} disabled={loading || saving}>
            {loading ? 'Loading…' : 'Reload'}
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={save} disabled={saving || loading}>
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}
      {!error && info && <div className="atopo-info">{info}</div>}

      {/* inline editor for the active level's name + elevation */}
      {activeLevel && (
        <div className="atopo-levelmeta glass">
          <label className="atopo-inline">
            <span>
              Level name{' '}
              <InfoTip text="Display name for this floor/level of the automation layout." example="Mezzanine" />
            </span>
            <input
              className="form-control"
              value={activeLevel.name}
              onChange={(e) => patchActiveLevel({ name: e.target.value })}
            />
          </label>
          <label className="atopo-inline">
            <span>
              Elevation (m){' '}
              <InfoTip text="Height of this level's floor above the ground datum, in metres." example="5" />
            </span>
            <input
              className="form-control"
              type="number"
              step={0.1}
              value={activeLevel.elevationM}
              onChange={(e) => patchActiveLevel({ elevationM: num(e.target.value) })}
            />
          </label>
        </div>
      )}

      <div className="atopo-body">
        {/* ---- left: equipment library ---- */}
        <aside className="atopo-panel atopo-library glass">
          <h3>Equipment library</h3>
          {library.length > 0 && (
            <label className="md-check atopo-unplaced">
              <input
                type="checkbox"
                checked={unplacedOnly}
                onChange={(e) => setUnplacedOnly(e.target.checked)}
              />
              Unplaced only
            </label>
          )}
          {library.length === 0 ? (
            <p className="atopo-muted">
              No equipment — create some in Master data → Equipment.
            </p>
          ) : libraryGroups.length === 0 ? (
            <p className="atopo-muted">All equipment is placed.</p>
          ) : (
            libraryGroups.map(([type, items]) => (
              <div key={type} className="atopo-libgroup">
                <div className="atopo-libgroup-head">{type}</div>
                {items.map((e) => {
                  const placedCount = e.id ? placementCounts.get(e.id) ?? 0 : 0
                  return (
                    <div key={e.id} className="atopo-librow">
                      <span className="atopo-librow-label">
                        {e.model || e.subtype || e.family}
                        {e.vendor ? <span className="atopo-muted"> · {e.vendor}</span> : null}
                        <span
                          className={`atopo-badge ${placedCount > 0 ? 'is-placed' : 'is-unplaced'}`}
                        >
                          {placedCount > 0 ? `placed ${placedCount}` : 'not placed'}
                        </span>
                      </span>
                      <button
                        type="button"
                        className="btn btn-outline btn-sm"
                        onClick={() => addFromLibrary(e)}
                        disabled={!activeLevelId}
                      >
                        + Add
                      </button>
                    </div>
                  )
                })}
              </div>
            ))
          )}
        </aside>

        {/* ---- center: 3D canvas ---- */}
        <div className="atopo-canvas glass">
          {levels.length === 0 ? (
            <div className="atopo-empty">
              <p>This warehouse has no automation levels yet.</p>
              <button type="button" className="btn btn-primary btn-sm" onClick={addLevel}>
                + Add the first level
              </button>
            </div>
          ) : (
            <>
            <Canvas camera={{ position: [12, 12, 12], fov: 50 }}>
              <color attach="background" args={['#081e16']} />
              <ambientLight intensity={0.6} />
              <directionalLight position={[10, 18, 8]} intensity={0.9} castShadow />
              <Grid
                args={[60, 60]}
                cellSize={1}
                cellThickness={0.6}
                cellColor="#234"
                sectionSize={5}
                sectionThickness={1}
                sectionColor="#3a6"
                fadeDistance={70}
                fadeStrength={1}
                infiniteGrid
                position={[0, 0, 0]}
              />
              <SceneContent
                items={levelEquipment}
                allItems={equipment}
                levels={levels}
                connections={connections}
                functionPoints={functionPoints}
                selectedConnId={selectedConnId}
                connectMode={connectMode}
                connectFrom={connectFrom}
                lib={libById}
                selectedId={selectedId}
                drawPath={drawPath}
                onSelect={setSelectedId}
                onConnectPick={handleConnectPick}
                onMove={(id, x, z, rotDeg) =>
                  patchEquipment(id, { posXM: x, posZM: z, rotationDeg: rotDeg })
                }
                onAppendWaypoint={appendWaypoint}
                onMoveWaypoint={moveWaypoint}
                onHandleDragChange={(active) => setOrbitEnabled(!active)}
              />
              <OrbitControls
                makeDefault
                enabled={orbitEnabled}
                enableDamping
                enablePan
                enableZoom
                enableRotate
                screenSpacePanning
                panSpeed={1.2}
                zoomSpeed={1.1}
                minDistance={2}
                maxDistance={150}
                maxPolarAngle={Math.PI / 2.05}
                mouseButtons={{ LEFT: THREE.MOUSE.ROTATE, MIDDLE: THREE.MOUSE.DOLLY, RIGHT: THREE.MOUSE.PAN }}
              />
            </Canvas>
            <div className="atopo-hint">
              {connectMode
                ? connectFrom
                  ? `Connect: from ${equipmentById.get(connectFrom)?.code ?? '?'} — click a target (or the source again to cancel)`
                  : 'Connect: click a source piece of equipment'
                : drawPath
                  ? 'Draw mode: click the floor to add conveyor waypoints'
                  : 'Drag to orbit · right-drag to pan · scroll to zoom'}
            </div>
            </>
          )}
        </div>

        {/* ---- right: properties ---- */}
        <aside className="atopo-panel atopo-props glass">
          <h3>Properties</h3>
          {!selected ? (
            <p className="atopo-muted">Select a piece of equipment to edit it, or add one from the library.</p>
          ) : (
            <div className="atopo-fields">
              <label className="atopo-field">
                <span>Code</span>
                <input
                  className="form-control"
                  value={selected.code}
                  onChange={(e) => patchEquipment(selected.id, { code: e.target.value })}
                />
              </label>
              <label className="atopo-field">
                <span>Level</span>
                <Select
                  ariaLabel="Level"
                  value={selected.levelId}
                  onChange={(v) => patchEquipment(selected.id, { levelId: v })}
                  options={levels.map((l) => ({ value: l.id, label: `${l.number} · ${l.name}` }))}
                />
              </label>
              <div className="atopo-grid2">
                <NumField label="Pos X (m)" value={selected.posXM} onChange={(v) => patchEquipment(selected.id, { posXM: v })} />
                <NumField label="Pos Z (m)" value={selected.posZM} onChange={(v) => patchEquipment(selected.id, { posZM: v })} />
                <NumField label="Pos Y (m)" value={selected.posYM} onChange={(v) => patchEquipment(selected.id, { posYM: v })} />
                <NumField label="Rotation (°)" value={selected.rotationDeg} onChange={(v) => patchEquipment(selected.id, { rotationDeg: v })} />
                <NumField label="Tilt (°)" value={selected.tiltDeg} onChange={(v) => patchEquipment(selected.id, { tiltDeg: v })} />
              </div>
              <div className="atopo-grid2">
                {!hasPath(selected) && (
                  <NumField label="Length (m)" value={selected.lengthM} onChange={(v) => patchEquipment(selected.id, { lengthM: v })} />
                )}
                <NumField label="Width (m)" value={selected.widthM} onChange={(v) => patchEquipment(selected.id, { widthM: v })} />
                <NumField label="Height (m)" value={selected.heightM} onChange={(v) => patchEquipment(selected.id, { heightM: v })} />
              </div>

              {selectedIsConveyor && (
                <ConveyorPathTools
                  eq={selected}
                  drawPath={drawPath}
                  onToggleDraw={() => setDrawPath((d) => !d)}
                  onPatch={(patch) => patchEquipment(selected.id, patch)}
                />
              )}

              <FunctionPointsPanel
                placedId={selected.id}
                points={selectedFunctionPoints}
                processTypes={selectedMeta?.processTypes ?? null}
                onAdd={addFunctionPoint}
                onDelete={deleteFunctionPoint}
              />

              {selectedIsAsrs && warehouseId && (
                <StorageAreasPanel
                  warehouseId={warehouseId}
                  equipmentId={selected.equipmentId ?? null}
                />
              )}

              <button type="button" className="btn btn-danger btn-sm atopo-delete" onClick={deleteSelected}>
                Delete equipment
              </button>
            </div>
          )}

          <ConnectionsPanel
            connections={connections}
            equipmentById={equipmentById}
            selectedConnId={selectedConnId}
            onSelect={(id) => setSelectedConnId((s) => (s === id ? null : id))}
            onDelete={deleteConnection}
          />
        </aside>
      </div>

      <Styles />
    </div>
  )
}

function NumField({
  label,
  value,
  onChange,
}: {
  label: string
  value: number
  onChange: (v: number) => void
}) {
  return (
    <label className="atopo-field">
      <span>{label}</span>
      <input
        className="form-control"
        type="number"
        step={0.1}
        value={value}
        onChange={(e) => onChange(num(e.target.value))}
      />
    </label>
  )
}

// Path-editing tools shown in the properties panel for a selected conveyor.
function ConveyorPathTools({
  eq,
  drawPath,
  onToggleDraw,
  onPatch,
}: {
  eq: AutomationEquipment
  drawPath: boolean
  onToggleDraw: () => void
  onPatch: (patch: Partial<AutomationEquipment>) => void
}) {
  const path = Array.isArray(eq.path) ? eq.path : []
  const count = path.length

  // Seed a two-point path from the current straight box: the two centreline endpoints,
  // posX/posZ ± length/2 projected along the box rotation (yaw about Y).
  const startFromBox = () => {
    const yaw = eq.rotationDeg * DEG
    const hx = (Math.cos(yaw) * eq.lengthM) / 2
    const hz = (Math.sin(yaw) * eq.lengthM) / 2
    onPatch({
      path: [
        [+(eq.posXM - hx).toFixed(3), +(eq.posZM - hz).toFixed(3)],
        [+(eq.posXM + hx).toFixed(3), +(eq.posZM + hz).toFixed(3)],
      ],
    })
  }

  const removeLast = () => {
    if (count === 0) return
    onPatch({ path: count <= 1 ? null : path.slice(0, -1) })
  }

  return (
    <div className="atopo-pathtools">
      <div className="atopo-pathtools-head">
        Conveyor path{' '}
        <InfoTip
          text="Draw a centreline of waypoints so this conveyor renders as a polyline (corners, turns, loops) instead of a single straight box."
          example="add corners to route around a column"
        />
      </div>
      <div className="atopo-pathcount">
        {count === 0
          ? 'No path — rendering as a straight box.'
          : `${count} waypoint${count === 1 ? '' : 's'}${count < 2 ? ' (need ≥ 2 to draw segments)' : ''}`}
      </div>

      <button
        type="button"
        className={`btn btn-sm ${drawPath ? 'btn-primary' : 'btn-outline'} atopo-pathbtn`}
        onClick={onToggleDraw}
      >
        {drawPath ? 'Drawing… click floor to add (stop)' : 'Draw path'}
      </button>

      {count === 0 && (
        <button type="button" className="btn btn-outline btn-sm atopo-pathbtn" onClick={startFromBox}>
          Start path from box
        </button>
      )}

      <label className="md-check atopo-pathcheck">
        <input
          type="checkbox"
          checked={!!eq.closed}
          onChange={(e) => onPatch({ closed: e.target.checked })}
        />
        Closed loop{' '}
        <InfoTip text="When on, the path loops back from the last waypoint to the first." example="a recirculating sorter loop" />
      </label>

      <div className="atopo-pathrow">
        <button
          type="button"
          className="btn btn-outline btn-sm"
          onClick={removeLast}
          disabled={count === 0}
        >
          Remove last point
        </button>
        <button
          type="button"
          className="btn btn-outline btn-sm"
          onClick={() => onPatch({ path: null })}
          disabled={count === 0}
        >
          Clear path
        </button>
      </div>
    </div>
  )
}

// Collapsible list of equipment-to-equipment connections with per-row delete + highlight-select.
function ConnectionsPanel({
  connections,
  equipmentById,
  selectedConnId,
  onSelect,
  onDelete,
}: {
  connections: AutomationConnection[]
  equipmentById: Map<string, AutomationEquipment>
  selectedConnId: string | null
  onSelect: (id: string) => void
  onDelete: (id: string) => void
}) {
  const [open, setOpen] = useState(true)
  return (
    <div className="atopo-conns">
      <button
        type="button"
        className="atopo-conns-head"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
      >
        <span>Connections ({connections.length})</span>
        <span className="atopo-conns-chevron">{open ? '▾' : '▸'}</span>
      </button>
      {open &&
        (connections.length === 0 ? (
          <p className="atopo-muted atopo-conns-empty">
            None yet — use Connect to link two pieces of equipment.
          </p>
        ) : (
          <ul className="atopo-conns-list">
            {connections.map((c) => {
              const from = equipmentById.get(c.fromPlacedId)
              const to = equipmentById.get(c.toPlacedId)
              const dangling = !from || !to
              return (
                <li
                  key={c.id}
                  className={`atopo-conns-row${c.id === selectedConnId ? ' is-active' : ''}`}
                >
                  <button
                    type="button"
                    className="atopo-conns-label"
                    onClick={() => onSelect(c.id)}
                    title={dangling ? 'One endpoint is missing from this layout' : 'Highlight this link'}
                  >
                    {from?.code ?? '?'} → {to?.code ?? '?'}
                    {dangling ? <span className="atopo-muted"> · dangling</span> : null}
                  </button>
                  <button
                    type="button"
                    className="btn btn-danger btn-sm"
                    onClick={() => onDelete(c.id)}
                  >
                    Delete
                  </button>
                </li>
              )
            })}
          </ul>
        ))}
    </div>
  )
}

// Function points (process points) on the selected placed equipment: list + add form.
function FunctionPointsPanel({
  placedId,
  points,
  processTypes,
  onAdd,
  onDelete,
}: {
  placedId: string
  points: AutomationFunctionPoint[]
  // The library equipment's declared process types (preferred Select options), or null.
  processTypes: string[] | null
  onAdd: (fp: AutomationFunctionPoint) => void
  onDelete: (id: string) => void
}) {
  const typeOptions = processTypes && processTypes.length > 0 ? processTypes : [...FUNCTION_TYPES]
  const [functionType, setFunctionType] = useState<string>(typeOptions[0] ?? FUNCTION_TYPES[0])
  const [name, setName] = useState('')
  const [offsetM, setOffsetM] = useState('0')
  const [side, setSide] = useState('')
  const [nodeCode, setNodeCode] = useState('')

  // Keep the selected functionType valid if the options change (e.g. selecting a different item).
  useEffect(() => {
    if (!typeOptions.includes(functionType)) setFunctionType(typeOptions[0] ?? FUNCTION_TYPES[0])
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placedId])

  const add = () => {
    onAdd({
      id: crypto.randomUUID(),
      placedId,
      functionType,
      name: name.trim() || null,
      offsetM: num(offsetM),
      side: side || null,
      nodeCode: nodeCode.trim() || null,
      status: 'ACTIVE',
    })
    setName('')
    setOffsetM('0')
    setSide('')
    setNodeCode('')
  }

  return (
    <div className="atopo-fps">
      <div className="atopo-fps-head">
        Function points{' '}
        <InfoTip
          text="Process points on this equipment — scanners, label applicators, diverts, DWS, query points, wrappers, induct/discharge. Each sits at an offset along the equipment."
          example="a scanner 1.5 m in on the left"
        />
      </div>

      {points.length === 0 ? (
        <p className="atopo-muted atopo-fps-empty">None yet.</p>
      ) : (
        <ul className="atopo-fps-list">
          {points.map((fp) => (
            <li key={fp.id} className="atopo-fps-row">
              <span className="atopo-fps-label">
                <span className="atopo-fps-type" style={{ color: functionColor(fp.functionType) }}>
                  {fp.functionType}
                </span>
                {fp.name ? <span> · {fp.name}</span> : null}
                <span className="atopo-muted">
                  {' '}
                  @ {fp.offsetM} m{fp.side ? ` · ${fp.side}` : ''}
                </span>
              </span>
              <button
                type="button"
                className="btn btn-danger btn-sm"
                onClick={() => onDelete(fp.id)}
              >
                Delete
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="atopo-fps-form">
        <label className="atopo-field">
          <span>Function type</span>
          <Select
            ariaLabel="Function type"
            value={functionType}
            onChange={setFunctionType}
            options={typeOptions.map((t) => ({ value: t, label: t }))}
          />
        </label>
        <label className="atopo-field">
          <span>Name</span>
          <input
            className="form-control"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="optional"
          />
        </label>
        <div className="atopo-grid2">
          <label className="atopo-field">
            <span>Offset (m)</span>
            <input
              className="form-control"
              type="number"
              step={0.1}
              value={offsetM}
              onChange={(e) => setOffsetM(e.target.value)}
            />
          </label>
          <label className="atopo-field">
            <span>Side</span>
            <Select
              ariaLabel="Side"
              value={side}
              onChange={setSide}
              options={[
                { value: '', label: '—' },
                { value: 'LEFT', label: 'LEFT' },
                { value: 'RIGHT', label: 'RIGHT' },
              ]}
            />
          </label>
        </div>
        <label className="atopo-field">
          <span>
            Node code{' '}
            <InfoTip
              text="Optional — maps this point to a conveyor routing node so material-flow routes can reference it."
              example="DIV-12"
            />
          </span>
          <input
            className="form-control"
            value={nodeCode}
            onChange={(e) => setNodeCode(e.target.value)}
            placeholder="optional"
          />
        </label>
        <button type="button" className="btn btn-outline btn-sm" onClick={add}>
          + Add function point
        </button>
      </div>
    </div>
  )
}

// ASRS → storage-area linking. Lists the warehouse's storage blocks with a checkbox that binds
// each block's master-data `equipmentId` to this placed equipment's library equipment id. Persists
// immediately to master-data (independent of the topology Save).
function StorageAreasPanel({
  warehouseId,
  equipmentId,
}: {
  warehouseId: string
  // The selected placed equipment's master-data (library) equipment id.
  equipmentId: string | null
}) {
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setBlocks(await listStorageBlocks(warehouseId))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  useEffect(() => {
    refresh()
  }, [refresh])

  const toggle = async (block: StorageBlock, checked: boolean) => {
    if (!block.id) return
    setBusyId(block.id)
    setError(null)
    try {
      await updateStorageBlock(block.id, {
        ...block,
        equipmentId: checked ? equipmentId : null,
      })
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="atopo-areas">
      <div className="atopo-areas-head">
        Storage areas{' '}
        <InfoTip
          text="Bind master-data storage blocks to this ASRS so stock in those blocks is handled by this system. Saves immediately — independent of the topology Save."
          example="link the AutoStore grid's block"
        />
      </div>

      {!equipmentId && (
        <p className="atopo-muted atopo-areas-empty">
          This placement isn't linked to a master-data equipment, so it can't own a storage area.
        </p>
      )}
      {error && <div className="alert alert-danger atopo-areas-error">{error}</div>}
      {loading ? (
        <p className="atopo-muted atopo-areas-empty">Loading storage blocks…</p>
      ) : blocks.length === 0 ? (
        <p className="atopo-muted atopo-areas-empty">
          No storage blocks — create some in Master data → Storage blocks.
        </p>
      ) : (
        <ul className="atopo-areas-list">
          {blocks.map((b) => {
            const linkedHere = !!equipmentId && b.equipmentId === equipmentId
            const linkedElsewhere = !!b.equipmentId && b.equipmentId !== equipmentId
            return (
              <li key={b.id} className="atopo-areas-row">
                <label className={`md-check atopo-areas-check${linkedElsewhere ? ' is-disabled' : ''}`}>
                  <input
                    type="checkbox"
                    checked={linkedHere}
                    disabled={!equipmentId || linkedElsewhere || busyId === b.id}
                    onChange={(e) => toggle(b, e.target.checked)}
                  />
                  <span className="atopo-areas-code">{b.code}</span>
                  <span className="atopo-muted"> · {b.storageType}</span>
                </label>
                {linkedElsewhere && (
                  <span className="atopo-muted atopo-areas-note">linked to another equipment</span>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

// =========================================================================
// 3D scene
// =========================================================================

interface SceneContentProps {
  items: AutomationEquipment[]
  // Every placed item across all levels — used to resolve connection endpoints (incl. cross-level).
  allItems: AutomationEquipment[]
  levels: AutomationLevel[]
  connections: AutomationConnection[]
  // All function points; we render the ones whose placedId is on the active level.
  functionPoints: AutomationFunctionPoint[]
  selectedConnId: string | null
  connectMode: boolean
  connectFrom: string | null
  lib: Map<string, Equipment>
  selectedId: string | null
  drawPath: boolean
  onSelect: (id: string | null) => void
  onConnectPick: (id: string) => void
  onMove: (id: string, x: number, z: number, rotationDeg: number) => void
  onAppendWaypoint: (id: string, x: number, z: number) => void
  onMoveWaypoint: (id: string, index: number, x: number, z: number) => void
  onHandleDragChange: (active: boolean) => void
}

function SceneContent({
  items,
  allItems,
  levels,
  connections,
  functionPoints,
  selectedConnId,
  connectMode,
  connectFrom,
  lib,
  selectedId,
  drawPath,
  onSelect,
  onConnectPick,
  onMove,
  onAppendWaypoint,
  onMoveWaypoint,
  onHandleDragChange,
}: SceneContentProps) {
  const byId = useMemo(() => {
    const m = new Map<string, AutomationEquipment>()
    for (const e of allItems) m.set(e.id, e)
    return m
  }, [allItems])

  return (
    <group>
      {/* The (invisible) ground plane. In draw mode it appends a waypoint to the selected
          conveyor; in connect mode an empty-space click cancels the in-progress pick; otherwise
          a plain left-click deselects. */}
      <mesh
        rotation-x={-Math.PI / 2}
        position={[0, -0.001, 0]}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          if (e.button !== 0) return
          if (connectMode) {
            e.stopPropagation()
            if (connectFrom) onConnectPick(connectFrom) // re-pick source id => cancel
            return
          }
          if (drawPath && selectedId) {
            // Suppress deselect while drawing; append the clicked floor point as a waypoint.
            e.stopPropagation()
            onAppendWaypoint(selectedId, e.point.x, e.point.z)
            return
          }
          onSelect(null)
        }}
      >
        <planeGeometry args={[200, 200]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>

      {/* Connection lines — drawn for every link whose endpoints both currently exist (any level). */}
      {connections.map((c) => {
        const from = byId.get(c.fromPlacedId)
        const to = byId.get(c.toPlacedId)
        if (!from || !to) return null
        return (
          <ConnectionLine
            key={c.id}
            a={worldCenter(from, levels)}
            b={worldCenter(to, levels)}
            active={c.id === selectedConnId}
          />
        )
      })}

      {items.map((eq) => (
        <EquipmentMesh
          key={eq.id}
          eq={eq}
          conveyor={isConveyor(eq, lib)}
          color={colorFor(eq, lib)}
          selected={eq.id === selectedId}
          // In connect mode highlight the chosen source and route clicks to the connect picker.
          connectMode={connectMode}
          connectSource={eq.id === connectFrom}
          onSelect={() => (connectMode ? onConnectPick(eq.id) : onSelect(eq.id))}
          onMove={(x, z, rot) => onMove(eq.id, x, z, rot)}
          onMoveWaypoint={(index, x, z) => onMoveWaypoint(eq.id, index, x, z)}
          onHandleDragChange={onHandleDragChange}
        />
      ))}

      {/* Function-point markers — one per point on an equipment visible on the active level.
          Clicking a marker selects its equipment (or, in connect mode, picks it as an endpoint). */}
      {functionPoints.map((fp) => {
        const eq = byId.get(fp.placedId)
        if (!eq) return null
        // Only render markers for equipment shown on the active level.
        if (!items.some((it) => it.id === eq.id)) return null
        return (
          <FunctionPointMarker
            key={fp.id}
            fp={fp}
            eq={eq}
            levels={levels}
            onSelect={() => (connectMode ? onConnectPick(eq.id) : onSelect(eq.id))}
          />
        )
      })}
    </group>
  )
}

// A small marker (cone) with a short type label, placed at the function point's offset along its
// equipment, nudged to the requested side, and sitting at the top of the equipment. It rides along
// with the equipment because its position is recomputed from the live equipment each render.
function FunctionPointMarker({
  fp,
  eq,
  levels,
  onSelect,
}: {
  fp: AutomationFunctionPoint
  eq: AutomationEquipment
  levels: AutomationLevel[]
  onSelect: () => void
}) {
  const elev = levels.find((l) => l.id === eq.levelId)?.elevationM ?? 0
  const at = pointAlong(eq, fp.offsetM)
  // Left/right is perpendicular to travel direction on the ground plane. With dir (dx,dz),
  // the right-hand normal is (dz, -dx); left is the negation.
  const half = eq.widthM / 2
  let ox = 0
  let oz = 0
  if (fp.side === 'LEFT') {
    ox = -at.dz * half
    oz = at.dx * half
  } else if (fp.side === 'RIGHT') {
    ox = at.dz * half
    oz = -at.dx * half
  }
  const top = elev + eq.heightM + eq.posYM
  const color = functionColor(fp.functionType)
  const short = FUNCTION_SHORT[fp.functionType] ?? fp.functionType
  return (
    <group position={[at.x + ox, top, at.z + oz]}>
      <mesh
        position={[0, 0.25, 0]}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          e.stopPropagation()
          onSelect()
        }}
      >
        <coneGeometry args={[0.16, 0.5, 16]} />
        <meshStandardMaterial color={color} emissive={color} emissiveIntensity={0.45} />
      </mesh>
      <Html position={[0, 0.7, 0]} center distanceFactor={16} occlude={false}>
        <div className="atopo-fpmarker" style={{ borderColor: color, color }}>
          {short}
        </div>
      </Html>
    </group>
  )
}

// A line between two world points with a small cone arrowhead at the "to" end.
function ConnectionLine({
  a,
  b,
  active,
}: {
  a: [number, number, number]
  b: [number, number, number]
  active: boolean
}) {
  const color = active ? '#8DC63F' : '#f0a85a'
  const va = new THREE.Vector3(a[0], a[1], a[2])
  const vb = new THREE.Vector3(b[0], b[1], b[2])
  const dir = new THREE.Vector3().subVectors(vb, va)
  const len = dir.length()
  // Orient a +Y cone to point along the link direction (arrowhead at the "to" end).
  const quat = new THREE.Quaternion()
  if (len > 1e-4) {
    quat.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir.clone().normalize())
  }
  return (
    <group>
      <Line points={[a, b]} color={color} lineWidth={active ? 3 : 2} />
      {len > 1e-4 && (
        <mesh position={[b[0], b[1], b[2]]} quaternion={quat}>
          <coneGeometry args={[0.18, 0.5, 12]} />
          <meshStandardMaterial color={color} emissive={color} emissiveIntensity={0.3} />
        </mesh>
      )}
    </group>
  )
}

interface EquipmentMeshProps {
  eq: AutomationEquipment
  conveyor: boolean
  color: string
  selected: boolean
  // Connect mode on → clicks pick connection endpoints; the chosen source is highlighted.
  connectMode: boolean
  connectSource: boolean
  onSelect: () => void
  onMove: (x: number, z: number, rotationDeg: number) => void
  onMoveWaypoint: (index: number, x: number, z: number) => void
  onHandleDragChange: (active: boolean) => void
}

function EquipmentMesh({
  eq,
  conveyor,
  color,
  selected,
  connectMode,
  connectSource,
  onSelect,
  onMove,
  onMoveWaypoint,
  onHandleDragChange,
}: EquipmentMeshProps) {
  // Highlight either the editor selection or (in connect mode) the chosen source.
  const highlight = connectMode ? connectSource : selected
  const highlightColor = connectMode ? '#f0a85a' : '#8DC63F'

  // Conveyor with a usable polyline → render as connected segments + (when selected) handles.
  // While connecting we suppress waypoint handles so clicks register as connect picks.
  if (conveyor && hasPath(eq)) {
    return (
      <ConveyorPath
        eq={eq}
        color={color}
        selected={highlight}
        editable={selected && !connectMode}
        onSelect={onSelect}
        onMoveWaypoint={onMoveWaypoint}
        onHandleDragChange={onHandleDragChange}
      />
    )
  }

  // Captured at drag start so cumulative gizmo deltas apply to a fixed base, not live state.
  const dragBase = useRef<{ x: number; z: number; rotDeg: number } | null>(null)
  const y = eq.heightM / 2 + eq.posYM
  const box = (
    <mesh
      castShadow
      onPointerDown={(e: ThreeEvent<PointerEvent>) => {
        e.stopPropagation()
        onSelect()
      }}
    >
      <boxGeometry args={[eq.lengthM, eq.heightM, eq.widthM]} />
      <meshStandardMaterial
        color={color}
        emissive={highlight ? highlightColor : '#000000'}
        emissiveIntensity={highlight ? 0.5 : 0}
        metalness={0.1}
        roughness={0.7}
      />
      {highlight && (
        // Simple wireframe outline on the highlighted box.
        <lineSegments>
          <edgesGeometry args={[new THREE.BoxGeometry(eq.lengthM * 1.02, eq.heightM * 1.02, eq.widthM * 1.02)]} />
          <lineBasicMaterial color={highlightColor} />
        </lineSegments>
      )}
      <Html position={[0, eq.heightM / 2 + 0.4, 0]} center distanceFactor={18} occlude={false}>
        <div className="atopo-label">{eq.code}</div>
      </Html>
    </mesh>
  )

  // When selected (and not connecting), wrap in PivotControls for drag-move + rotate.
  if (selected && !connectMode) {
    return (
      <PivotControls
        anchor={[0, 0, 0]}
        // Re-mount per equipment so the gizmo resets cleanly when selection changes.
        key={eq.id}
        scale={Math.max(1.5, Math.max(eq.lengthM, eq.widthM))}
        depthTest={false}
        disableScaling
        // autoTransform off: our React state is the source of truth for the group's transform,
        // so the gizmo's local matrix `l` is cumulative-from-drag-start and we add it to the base
        // captured at drag start — no runaway accumulation as state updates each frame.
        autoTransform={false}
        activeAxes={[true, false, true]}
        onDragStart={() => {
          dragBase.current = { x: eq.posXM, z: eq.posZM, rotDeg: eq.rotationDeg }
        }}
        onDrag={(l) => {
          const base = dragBase.current
          if (!base) return
          const pos = new THREE.Vector3()
          const quat = new THREE.Quaternion()
          const scl = new THREE.Vector3()
          l.decompose(pos, quat, scl)
          const euler = new THREE.Euler().setFromQuaternion(quat, 'YXZ')
          const rotDeg = base.rotDeg + euler.y / DEG
          onMove(
            +(base.x + pos.x).toFixed(3),
            +(base.z + pos.z).toFixed(3),
            +rotDeg.toFixed(2),
          )
        }}
      >
        <group
          position={[eq.posXM, y, eq.posZM]}
          rotation={[eq.tiltDeg * DEG, eq.rotationDeg * DEG, 0]}
          rotation-order="YXZ"
        >
          {box}
        </group>
      </PivotControls>
    )
  }

  return (
    <group
      position={[eq.posXM, y, eq.posZM]}
      rotation={[eq.tiltDeg * DEG, eq.rotationDeg * DEG, 0]}
      rotation-order="YXZ"
    >
      {box}
    </group>
  )
}

// =========================================================================
// Conveyor polyline (corners / turns / loops)
// =========================================================================

interface ConveyorPathProps {
  eq: AutomationEquipment
  color: string
  selected: boolean
  // When true, draggable waypoint handles are shown (suppressed while in connect mode).
  editable: boolean
  onSelect: () => void
  onMoveWaypoint: (index: number, x: number, z: number) => void
  onHandleDragChange: (active: boolean) => void
}

// Renders a conveyor as a chain of box segments following its centreline waypoints, plus
// (when editable) draggable sphere handles to edit each waypoint on the XZ ground plane.
function ConveyorPath({
  eq,
  color,
  selected,
  editable,
  onSelect,
  onMoveWaypoint,
  onHandleDragChange,
}: ConveyorPathProps) {
  const path = (eq.path ?? []) as number[][]
  const y = eq.heightM / 2 + eq.posYM

  // Build the list of segments (consecutive pairs; plus last→first when closed).
  const segments: { mx: number; mz: number; len: number; yaw: number }[] = []
  const pairCount = eq.closed ? path.length : path.length - 1
  for (let i = 0; i < pairCount; i++) {
    const a = path[i]
    const b = path[(i + 1) % path.length]
    if (!a || !b) continue
    const dx = b[0] - a[0]
    const dz = b[1] - a[1]
    const len = Math.hypot(dx, dz)
    if (len < 1e-4) continue
    segments.push({
      mx: (a[0] + b[0]) / 2,
      mz: (a[1] + b[1]) / 2,
      len,
      // z maps to world Z: yaw about Y rotates the box's X length into the segment direction.
      yaw: Math.atan2(dz, dx),
    })
  }

  const first = path[0]

  return (
    <group>
      {segments.map((s, i) => (
        <group key={i} position={[s.mx, y, s.mz]} rotation={[0, -s.yaw, 0]}>
          <mesh
            castShadow
            onPointerDown={(e: ThreeEvent<PointerEvent>) => {
              e.stopPropagation()
              onSelect()
            }}
          >
            <boxGeometry args={[s.len, eq.heightM, eq.widthM]} />
            <meshStandardMaterial
              color={color}
              emissive={selected ? '#8DC63F' : '#000000'}
              emissiveIntensity={selected ? 0.5 : 0}
              metalness={0.1}
              roughness={0.7}
            />
          </mesh>
        </group>
      ))}

      {/* Code label near the first waypoint. */}
      {first && (
        <Html
          position={[first[0], y + eq.heightM / 2 + 0.4, first[1]]}
          center
          distanceFactor={18}
          occlude={false}
        >
          <div className="atopo-label">{eq.code}</div>
        </Html>
      )}

      {/* Editable waypoint handles, only when this conveyor is the editable selection. */}
      {editable &&
        path.map((p, i) => (
          <WaypointHandle
            key={i}
            x={p[0]}
            z={p[1]}
            y={y}
            radius={Math.max(0.2, eq.widthM * 0.35)}
            onDrag={(x, z) => onMoveWaypoint(i, x, z)}
            onDragChange={onHandleDragChange}
          />
        ))}
    </group>
  )
}

// A draggable sphere at a single waypoint. Dragging raycasts against a horizontal plane at the
// waypoint's y and updates the point live. Disables OrbitControls for the duration of the drag.
function WaypointHandle({
  x,
  z,
  y,
  radius,
  onDrag,
  onDragChange,
}: {
  x: number
  z: number
  y: number
  radius: number
  onDrag: (x: number, z: number) => void
  onDragChange: (active: boolean) => void
}) {
  const dragging = useRef(false)
  // Horizontal plane at the handle's height; we intersect the pointer ray with it each move.
  const plane = useRef(new THREE.Plane(new THREE.Vector3(0, 1, 0), -y))
  const hit = useRef(new THREE.Vector3())

  return (
    <mesh
      position={[x, y, z]}
      onPointerDown={(e: ThreeEvent<PointerEvent>) => {
        if (e.button !== 0) return
        e.stopPropagation()
        dragging.current = true
        onDragChange(true)
        ;(e.target as Element).setPointerCapture?.(e.pointerId)
      }}
      onPointerMove={(e: ThreeEvent<PointerEvent>) => {
        if (!dragging.current) return
        e.stopPropagation()
        // Keep the plane in sync with the current y, then intersect the pointer ray.
        plane.current.set(new THREE.Vector3(0, 1, 0), -y)
        const p = e.ray.intersectPlane(plane.current, hit.current)
        if (p) onDrag(p.x, p.z)
      }}
      onPointerUp={(e: ThreeEvent<PointerEvent>) => {
        if (!dragging.current) return
        e.stopPropagation()
        dragging.current = false
        onDragChange(false)
        ;(e.target as Element).releasePointerCapture?.(e.pointerId)
      }}
    >
      <sphereGeometry args={[radius, 16, 16]} />
      <meshStandardMaterial color="#8DC63F" emissive="#3a6" emissiveIntensity={0.4} depthTest={false} />
    </mesh>
  )
}

// =========================================================================
// Scoped styles
// =========================================================================

function Styles() {
  return (
    <style>{`
      .atopo { display: flex; flex-direction: column; gap: .6rem; }
      .atopo-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap; }
      .atopo-levels { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
      .atopo-actions { display: flex; align-items: center; gap: .5rem; }
      .atopo-dirty { font-size: .75rem; color: #f4b860; }
      .atopo-leveltab {
        padding: .35rem .8rem; border-radius: 999px; cursor: pointer; font-size: .8125rem;
        background: var(--glass-bg); color: var(--text); border: 1px solid var(--glass-border);
        font-family: var(--font-body); transition: all .15s;
      }
      .atopo-leveltab:hover { border-color: var(--glass-border-bright); }
      .atopo-leveltab.is-active {
        background: rgba(141, 198, 63, .15); color: var(--herbal-lime); border-color: var(--glass-border-bright);
      }
      .atopo-info { font-size: .8rem; color: var(--text-dim); }
      .atopo-levelmeta { display: flex; gap: 1rem; padding: .6rem .8rem; flex-wrap: wrap; }
      .atopo-inline { display: flex; flex-direction: column; gap: .25rem; min-width: 200px; flex: 1; margin: 0; }
      .atopo-inline > span { font-size: .8125rem; color: var(--text-dim); }
      .atopo-body { display: grid; grid-template-columns: 260px 1fr 280px; gap: .6rem; align-items: stretch; }
      .atopo-panel { padding: .8rem; overflow-y: auto; max-height: 70vh; }
      .atopo-panel h3 { font-size: .95rem; margin: 0 0 .6rem; }
      .atopo-canvas { padding: 0; height: 70vh; overflow: hidden; position: relative; }
      .atopo-canvas canvas { display: block; }
      .atopo-hint {
        position: absolute; left: 12px; bottom: 12px; z-index: 2; pointer-events: none;
        padding: .3rem .6rem; border-radius: 8px; font-size: .72rem; letter-spacing: .02em;
        color: rgba(214, 228, 220, .85); background: rgba(8, 30, 22, .6);
        border: 1px solid var(--glass-border);
      }
      .atopo-empty {
        height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
        gap: .8rem; color: var(--text-dim); text-align: center; padding: 1rem;
      }
      .atopo-muted { color: var(--text-dim); font-size: .8125rem; }
      .atopo-libgroup { margin-bottom: .8rem; }
      .atopo-libgroup-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: var(--herbal-lime); margin-bottom: .3rem;
      }
      .atopo-librow {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .35rem .5rem; border-radius: 8px; font-size: .8125rem;
        border: 1px solid var(--glass-border); margin-bottom: .3rem;
      }
      .atopo-librow-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: flex; align-items: center; gap: .35rem; }
      .atopo-unplaced { margin: 0 0 .6rem; font-size: .8rem; }
      .atopo-badge {
        font-family: var(--font-mono); font-size: .62rem; text-transform: uppercase; letter-spacing: .05em;
        padding: 1px 6px; border-radius: 999px; border: 1px solid var(--glass-border); white-space: nowrap;
      }
      .atopo-badge.is-placed { color: var(--herbal-lime); border-color: rgba(141, 198, 63, .4); background: rgba(141, 198, 63, .12); }
      .atopo-badge.is-unplaced { color: var(--text-dim); }
      .atopo-conns { margin-top: .8rem; border-top: 1px solid var(--glass-border); padding-top: .6rem; }
      .atopo-conns-head {
        width: 100%; display: flex; align-items: center; justify-content: space-between;
        background: none; border: none; cursor: pointer; padding: 0; color: var(--text);
        font-family: var(--font-body); font-size: .9rem; font-weight: 600;
      }
      .atopo-conns-chevron { color: var(--text-dim); }
      .atopo-conns-empty { margin: .5rem 0 0; }
      .atopo-conns-list { list-style: none; margin: .5rem 0 0; padding: 0; display: flex; flex-direction: column; gap: .3rem; }
      .atopo-conns-row {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .3rem .45rem; border-radius: 8px; border: 1px solid var(--glass-border);
      }
      .atopo-conns-row.is-active { border-color: var(--glass-border-bright); background: rgba(141, 198, 63, .1); }
      .atopo-conns-label {
        flex: 1; text-align: left; background: none; border: none; cursor: pointer; padding: 0;
        color: var(--text); font-family: var(--font-mono); font-size: .8rem;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .atopo-fields { display: flex; flex-direction: column; gap: .6rem; }
      .atopo-field { display: flex; flex-direction: column; gap: .25rem; margin: 0; }
      .atopo-field > span { font-size: .8125rem; color: var(--text-dim); }
      .atopo-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; }
      .atopo-delete { margin-top: .4rem; }
      .atopo-pathtools {
        display: flex; flex-direction: column; gap: .5rem; margin-top: .4rem;
        padding: .6rem .7rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(141, 198, 63, .05);
      }
      .atopo-pathtools-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: var(--herbal-lime);
      }
      .atopo-pathcount { font-size: .8rem; color: var(--text-dim); }
      .atopo-pathbtn { width: 100%; }
      .atopo-pathcheck { margin: .1rem 0; }
      .atopo-pathrow { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; }
      .atopo-label {
        font-family: var(--font-mono); font-size: 11px; padding: 1px 6px; border-radius: 6px;
        background: rgba(8, 30, 22, .85); color: var(--text); border: 1px solid var(--glass-border-bright);
        white-space: nowrap; pointer-events: none; transform: translateY(-2px);
      }
      .atopo-fpmarker {
        font-family: var(--font-mono); font-size: 9px; line-height: 1; letter-spacing: .04em;
        padding: 1px 5px; border-radius: 999px; white-space: nowrap; pointer-events: none;
        background: rgba(8, 30, 22, .85); border: 1px solid currentColor; transform: translateY(-2px);
      }
      .atopo-fps {
        display: flex; flex-direction: column; gap: .5rem; margin-top: .4rem;
        padding: .6rem .7rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(94, 200, 224, .05);
      }
      .atopo-fps-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: #5ec8e0;
      }
      .atopo-fps-empty { margin: 0; }
      .atopo-fps-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .3rem; }
      .atopo-fps-row {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .3rem .45rem; border-radius: 8px; border: 1px solid var(--glass-border);
      }
      .atopo-fps-label {
        flex: 1; font-size: .78rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .atopo-fps-type { font-family: var(--font-mono); font-size: .72rem; font-weight: 600; }
      .atopo-fps-form { display: flex; flex-direction: column; gap: .5rem; margin-top: .2rem; }
      .atopo-areas {
        display: flex; flex-direction: column; gap: .5rem; margin-top: .4rem;
        padding: .6rem .7rem; border-radius: 10px;
        border: 1px solid var(--glass-border); background: rgba(122, 108, 192, .06);
      }
      .atopo-areas-head {
        font-family: var(--font-mono); font-size: .7rem; text-transform: uppercase; letter-spacing: .1em;
        color: #9d8fe0;
      }
      .atopo-areas-empty, .atopo-areas-error { margin: 0; }
      .atopo-areas-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: .25rem; }
      .atopo-areas-row {
        display: flex; align-items: center; justify-content: space-between; gap: .5rem;
        padding: .25rem .4rem; border-radius: 8px; border: 1px solid var(--glass-border);
      }
      .atopo-areas-check { margin: 0; font-size: .8rem; display: flex; align-items: center; gap: .4rem; }
      .atopo-areas-check.is-disabled { opacity: .55; }
      .atopo-areas-code { font-family: var(--font-mono); font-size: .78rem; }
      .atopo-areas-note { font-size: .68rem; white-space: nowrap; }
      @media (max-width: 1100px) {
        .atopo-body { grid-template-columns: 1fr; }
        .atopo-panel { max-height: none; }
      }
    `}</style>
  )
}
