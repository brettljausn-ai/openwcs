import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Canvas, type ThreeEvent } from '@react-three/fiber'
import { Grid, Html, OrbitControls, PivotControls, Text } from '@react-three/drei'
import * as THREE from 'three'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { listEquipment, type Equipment } from '../masterdata/api'
import {
  loadAutomationTopology,
  saveAutomationTopology,
  type AutomationConnection,
  type AutomationEquipment,
  type AutomationFunctionPoint,
  type AutomationLevel,
} from './automationApi'

const DEG = Math.PI / 180

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

function num(v: string, fallback = 0): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : fallback
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

  const patchEquipment = useCallback((id: string, patch: Partial<AutomationEquipment>) => {
    setEquipment((es) => es.map((e) => (e.id === id ? { ...e, ...patch } : e)))
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
    setSelectedId(null)
    setDirty(true)
  }, [selectedId])

  // Group the equipment library by type for the left panel.
  const libraryGroups = useMemo(() => {
    const groups = new Map<string, Equipment[]>()
    for (const e of library) {
      const key = equipmentTypeLabel(e)
      const arr = groups.get(key) ?? []
      arr.push(e)
      groups.set(key, arr)
    }
    return [...groups.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [library])

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
          {library.length === 0 ? (
            <p className="atopo-muted">
              No equipment — create some in Master data → Equipment.
            </p>
          ) : (
            libraryGroups.map(([type, items]) => (
              <div key={type} className="atopo-libgroup">
                <div className="atopo-libgroup-head">{type}</div>
                {items.map((e) => (
                  <div key={e.id} className="atopo-librow">
                    <span className="atopo-librow-label">
                      {e.model || e.subtype || e.family}
                      {e.vendor ? <span className="atopo-muted"> · {e.vendor}</span> : null}
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
                ))}
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
                lib={libById}
                selectedId={selectedId}
                onSelect={setSelectedId}
                onMove={(id, x, z, rotDeg) =>
                  patchEquipment(id, { posXM: x, posZM: z, rotationDeg: rotDeg })
                }
              />
              <OrbitControls makeDefault enableDamping />
            </Canvas>
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
                <NumField label="Length (m)" value={selected.lengthM} onChange={(v) => patchEquipment(selected.id, { lengthM: v })} />
                <NumField label="Width (m)" value={selected.widthM} onChange={(v) => patchEquipment(selected.id, { widthM: v })} />
                <NumField label="Height (m)" value={selected.heightM} onChange={(v) => patchEquipment(selected.id, { heightM: v })} />
              </div>
              <button type="button" className="btn btn-danger btn-sm atopo-delete" onClick={deleteSelected}>
                Delete equipment
              </button>
            </div>
          )}
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

// =========================================================================
// 3D scene
// =========================================================================

interface SceneContentProps {
  items: AutomationEquipment[]
  lib: Map<string, Equipment>
  selectedId: string | null
  onSelect: (id: string | null) => void
  onMove: (id: string, x: number, z: number, rotationDeg: number) => void
}

function SceneContent({ items, lib, selectedId, onSelect, onMove }: SceneContentProps) {
  return (
    <group>
      {/* Click the (invisible) ground plane to deselect. */}
      <mesh
        rotation-x={-Math.PI / 2}
        position={[0, -0.001, 0]}
        onPointerDown={(e: ThreeEvent<PointerEvent>) => {
          // Only deselect on a plain click on empty floor (not a drag of controls).
          if (e.button === 0) onSelect(null)
        }}
      >
        <planeGeometry args={[200, 200]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>

      {items.map((eq) => (
        <EquipmentMesh
          key={eq.id}
          eq={eq}
          color={colorFor(eq, lib)}
          selected={eq.id === selectedId}
          onSelect={() => onSelect(eq.id)}
          onMove={(x, z, rot) => onMove(eq.id, x, z, rot)}
        />
      ))}
    </group>
  )
}

interface EquipmentMeshProps {
  eq: AutomationEquipment
  color: string
  selected: boolean
  onSelect: () => void
  onMove: (x: number, z: number, rotationDeg: number) => void
}

function EquipmentMesh({ eq, color, selected, onSelect, onMove }: EquipmentMeshProps) {
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
        emissive={selected ? '#8DC63F' : '#000000'}
        emissiveIntensity={selected ? 0.5 : 0}
        metalness={0.1}
        roughness={0.7}
      />
      {selected && (
        // Simple wireframe outline on the selected box.
        <lineSegments>
          <edgesGeometry args={[new THREE.BoxGeometry(eq.lengthM * 1.02, eq.heightM * 1.02, eq.widthM * 1.02)]} />
          <lineBasicMaterial color="#8DC63F" />
        </lineSegments>
      )}
      <Html position={[0, eq.heightM / 2 + 0.4, 0]} center distanceFactor={18} occlude={false}>
        <div className="atopo-label">{eq.code}</div>
      </Html>
    </mesh>
  )

  // When selected, wrap in PivotControls for drag-move + rotate; write back on change.
  if (selected) {
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
      .atopo-canvas { padding: 0; height: 70vh; overflow: hidden; }
      .atopo-canvas canvas { display: block; }
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
      .atopo-librow-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .atopo-fields { display: flex; flex-direction: column; gap: .6rem; }
      .atopo-field { display: flex; flex-direction: column; gap: .25rem; margin: 0; }
      .atopo-field > span { font-size: .8125rem; color: var(--text-dim); }
      .atopo-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: .5rem; }
      .atopo-delete { margin-top: .4rem; }
      .atopo-label {
        font-family: var(--font-mono); font-size: 11px; padding: 1px 6px; border-radius: 6px;
        background: rgba(8, 30, 22, .85); color: var(--text); border: 1px solid var(--glass-border-bright);
        white-space: nowrap; pointer-events: none; transform: translateY(-2px);
      }
      @media (max-width: 1100px) {
        .atopo-body { grid-template-columns: 1fr; }
        .atopo-panel { max-height: none; }
      }
    `}</style>
  )
}
