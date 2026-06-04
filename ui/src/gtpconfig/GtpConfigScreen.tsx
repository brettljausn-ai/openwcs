import { useCallback, useEffect, useState } from 'react'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import { listWarehouses, Warehouse } from '../masterdata/api'
import {
  CreateStationBody,
  NODE_ROLES,
  NodeBody,
  NodeRole,
  OPERATING_MODES,
  OperatingMode,
  STATION_MODES,
  Station,
  StationMode,
  StationNode,
  UpdateStationBody,
  addNode,
  createStation,
  deleteNode,
  deleteStation,
  listStations,
  setSupportedModes,
  updateNode,
  updateStation,
} from './api'

// ---------------------------------------------------------------------------
// GTP workplace configuration (admin): CRUD for GTP stations/workplaces and
// their STOCK/ORDER nodes, plus configuration of the operating modes each
// workplace supports — against the gateway /api/gtp/stations/**. Stations are
// scoped to the selected warehouse (mirrors the master-data admin screen).
// ---------------------------------------------------------------------------

export default function GtpConfigScreen() {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [warehouseId, setWarehouseId] = useState('')
  const [whError, setWhError] = useState<string | null>(null)

  useEffect(() => {
    ;(async () => {
      try {
        setWhError(null)
        const list = await listWarehouses()
        setWarehouses(list)
        setWarehouseId((cur) => cur || (list[0]?.id ?? ''))
      } catch (e) {
        setWhError(errMsg(e))
      }
    })()
  }, [])

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Configuration</span>
        <h1>GTP workplaces</h1>
        <p>
          Configure goods-to-person workplaces (stations): their destination topology, the operating modes they
          support, and their STOCK / ORDER nodes. Workplaces are scoped to the selected warehouse.
        </p>
      </div>

      <div className="toolbar">
        <label style={{ margin: 0 }}>
          Warehouse{' '}
          <InfoTip
            text="The warehouse whose GTP workplaces you are configuring. All workplaces and nodes below are scoped to this site."
            example="WH-01 — Central DC"
          />
        </label>
        <Select
          ariaLabel="Warehouse"
          style={{ maxWidth: 320 }}
          value={warehouseId}
          onChange={(v) => setWarehouseId(v)}
          options={[
            { value: '', label: 'Select a warehouse…' },
            ...warehouses.map((w) => ({ value: w.id ?? '', label: `${w.code} — ${w.name}` })),
          ]}
        />
        {whError && <span className="muted">{whError}</span>}
      </div>

      {warehouseId ? (
        <StationsPanel warehouseId={warehouseId} />
      ) : (
        <div className="alert">Select a warehouse above to configure its GTP workplaces.</div>
      )}

      <Styles />
    </div>
  )
}

// =========================================================================
// Shared helpers / primitives (mirrors the master-data admin design system)
// =========================================================================

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function StatusBadge({ status }: { status?: string }) {
  const s = (status ?? '').toUpperCase()
  const cls = s === 'ACTIVE' ? 'badge-success' : s === 'INACTIVE' || s === 'ARCHIVED' ? 'badge-danger' : 'badge-warning'
  return <span className={`badge ${cls}`}>{status ?? '—'}</span>
}

function RoleBadge({ role }: { role: NodeRole }) {
  return <span className={`badge ${role === 'STOCK' ? 'badge-info' : 'badge-success'}`}>{role}</span>
}

function ModeBadges({ modes }: { modes: OperatingMode[] }) {
  if (!modes.length) return <span className="muted">—</span>
  return (
    <span className="gtp-badges">
      {modes.map((m) => (
        <span key={m} className="badge badge-info">
          {m}
        </span>
      ))}
    </span>
  )
}

function Dialog({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="dialog glass" onClick={(e) => e.stopPropagation()}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>
  )
}

function Field({ label, children, required }: { label: React.ReactNode; children: React.ReactNode; required?: boolean }) {
  return (
    <div className="gtp-field">
      <label>
        {label}
        {required && <span style={{ color: '#ff8a80' }}> *</span>}
      </label>
      {children}
    </div>
  )
}

/** Generic edit/create dialog shell with an error slot and Save/Cancel actions. */
function EditDialog({
  title,
  onClose,
  onSave,
  children,
  canSave = true,
}: {
  title: string
  onClose: () => void
  onSave: () => Promise<void>
  children: React.ReactNode
  canSave?: boolean
}) {
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function save() {
    setSaving(true)
    setError(null)
    try {
      await onSave()
      onClose()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setSaving(false)
    }
  }
  return (
    <Dialog title={title} onClose={onClose}>
      {error && <div className="alert alert-danger">{error}</div>}
      <div className="gtp-form">{children}</div>
      <div className="dialog-actions">
        <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={saving}>
          Cancel
        </button>
        <button className="btn btn-primary btn-sm" onClick={save} disabled={saving || !canSave}>
          {saving ? <span className="spin" /> : 'Save'}
        </button>
      </div>
    </Dialog>
  )
}

function ConfirmDelete({
  title,
  message,
  confirmLabel,
  onConfirm,
  onClose,
}: {
  title: string
  message: React.ReactNode
  confirmLabel: string
  onConfirm: () => Promise<void>
  onClose: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function go() {
    setBusy(true)
    setError(null)
    try {
      await onConfirm()
      onClose()
    } catch (e) {
      setError(errMsg(e))
      setBusy(false)
    }
  }
  return (
    <Dialog title={title} onClose={onClose}>
      {error && <div className="alert alert-danger">{error}</div>}
      <p>{message}</p>
      <div className="dialog-actions">
        <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button className="btn btn-danger btn-sm" onClick={go} disabled={busy}>
          {busy ? <span className="spin" /> : confirmLabel}
        </button>
      </div>
    </Dialog>
  )
}

/** Operating-mode multiselect via checkboxes. PICKING is always on (the base flow). */
function ModeCheckboxes({ value, onChange }: { value: OperatingMode[]; onChange: (v: OperatingMode[]) => void }) {
  function toggle(m: OperatingMode, on: boolean) {
    if (m === 'PICKING') return // always retained
    onChange(on ? [...new Set([...value, m])] : value.filter((x) => x !== m))
  }
  return (
    <div className="gtp-checks">
      {OPERATING_MODES.map((m) => {
        const checked = m === 'PICKING' || value.includes(m)
        return (
          <label key={m} className="gtp-check">
            <input
              type="checkbox"
              checked={checked}
              disabled={m === 'PICKING'}
              onChange={(e) => toggle(m, e.target.checked)}
            />
            {m}
            {m === 'PICKING' && <span className="muted"> (always)</span>}
          </label>
        )
      })}
    </div>
  )
}

function uuidOrNull(v: string): string | null {
  return v.trim() === '' ? null : v.trim()
}

// =========================================================================
// Stations panel (master list + selected-station node management)
// =========================================================================

function StationsPanel({ warehouseId }: { warehouseId: string }) {
  const [rows, setRows] = useState<Station[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [editing, setEditing] = useState<Station | 'new' | null>(null)
  const [deleting, setDeleting] = useState<Station | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setError(null)
      const list = await listStations(warehouseId)
      setRows(list)
      setSelectedId((cur) => (cur && list.some((s) => s.id === cur) ? cur : (list[0]?.id ?? null)))
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])
  useEffect(() => {
    load()
  }, [load])

  const selected = rows.find((s) => s.id === selectedId) ?? null

  return (
    <>
      <div className="glass card-pad gtp-panel">
        <div className="toolbar">
          <strong>Workplaces</strong>
          <div className="spacer" />
          <button className="btn btn-primary btn-sm" onClick={() => setEditing('new')}>
            + New workplace
          </button>
        </div>
        {error && <div className="alert alert-danger">{error}</div>}
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Name</th>
              <th>Topology</th>
              <th>Operating modes</th>
              <th>Nodes</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <Empty text="Loading…" />
            ) : rows.length === 0 ? (
              <Empty text="No GTP workplaces in this warehouse yet." />
            ) : (
              rows.map((s) => (
                <tr
                  key={s.id}
                  className={s.id === selectedId ? 'gtp-row-selected' : ''}
                  onClick={() => setSelectedId(s.id)}
                  style={{ cursor: 'pointer' }}
                >
                  <td>{s.code}</td>
                  <td>{s.name || '—'}</td>
                  <td>
                    <span className="badge badge-warning">{s.mode}</span>
                  </td>
                  <td>
                    <ModeBadges modes={s.supportedModes} />
                  </td>
                  <td>{s.nodes.length}</td>
                  <td>
                    <StatusBadge status={s.status} />
                  </td>
                  <td className="gtp-row-actions" onClick={(e) => e.stopPropagation()}>
                    <button className="btn btn-ghost btn-sm" onClick={() => setEditing(s)}>
                      Edit
                    </button>
                    <button className="btn btn-danger btn-sm" onClick={() => setDeleting(s)}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {selected && <NodesPanel station={selected} onChanged={load} />}

      {editing && (
        <StationDialog
          warehouseId={warehouseId}
          initial={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={(saved) => {
            setSelectedId(saved.id)
            load()
          }}
        />
      )}
      {deleting && (
        <ConfirmDelete
          title="Delete workplace"
          confirmLabel="Delete"
          message={
            <>
              Delete workplace <strong>{deleting.code}</strong> and all its nodes? This cannot be undone.
            </>
          }
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await deleteStation(deleting.id)
            await load()
          }}
        />
      )}
    </>
  )
}

function Empty({ text }: { text: string }) {
  return (
    <tr>
      <td colSpan={99} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
        {text}
      </td>
    </tr>
  )
}

// =========================================================================
// Station create/edit dialog (code, name, warehouse, topology mode, modes)
// =========================================================================

function StationDialog({
  warehouseId,
  initial,
  onClose,
  onSaved,
}: {
  warehouseId: string
  initial: Station | null
  onClose: () => void
  onSaved: (s: Station) => void
}) {
  const [code, setCode] = useState(initial?.code ?? '')
  const [name, setName] = useState(initial?.name ?? '')
  const [mode, setMode] = useState<StationMode>(initial?.mode ?? 'ORDER_LOCATION')
  const [status, setStatus] = useState(initial?.status ?? 'ACTIVE')
  const [modes, setModes] = useState<OperatingMode[]>(initial?.supportedModes ?? ['PICKING'])

  const valid = code.trim() !== ''

  return (
    <EditDialog
      title={initial ? 'Edit workplace' : 'New workplace'}
      canSave={valid}
      onClose={onClose}
      onSave={async () => {
        const withPicking = [...new Set<OperatingMode>(['PICKING', ...modes])]
        if (initial) {
          const body: UpdateStationBody = {
            code: code.trim(),
            name: name.trim() || null,
            mode,
            status,
            supportedModes: withPicking,
          }
          onSaved(await updateStation(initial.id, body))
        } else {
          const body: CreateStationBody = {
            warehouseId,
            code: code.trim(),
            name: name.trim() || null,
            mode,
            supportedModes: withPicking,
          }
          onSaved(await createStation(body))
        }
      }}
    >
      <div className="gtp-grid-2">
        <Field
          label={
            <>
              Code{' '}
              <InfoTip
                text="Short unique identifier for this workplace within the warehouse. Used in operator screens and on the device."
                example="GTP-03"
              />
            </>
          }
          required
        >
          <input className="form-control" value={code} onChange={(e) => setCode(e.target.value)} />
        </Field>
        <Field
          label={
            <>
              Name{' '}
              <InfoTip
                text="Optional human-friendly description of the workplace, shown alongside the code to help operators recognise it."
                example="Aisle 3 Put-wall"
              />
            </>
          }
        >
          <input
            className="form-control"
            value={name}
            placeholder="e.g. Aisle 3 Put-wall"
            onChange={(e) => setName(e.target.value)}
          />
        </Field>
      </div>
      <div className="gtp-grid-2">
        <Field
          label={
            <>
              Destination topology{' '}
              <InfoTip
                text="How order destinations are arranged: ORDER_LOCATION = one fixed/conveyor target per order; PUT_WALL = many cubbies the operator distributes into."
                example="PUT_WALL"
              />
            </>
          }
          required
        >
          <Select
            ariaLabel="Destination topology"
            value={mode}
            onChange={(v) => setMode(v as StationMode)}
            options={STATION_MODES.map((m) => ({ value: m, label: m }))}
          />
        </Field>
        {initial && (
          <Field
            label={
              <>
                Status{' '}
                <InfoTip
                  text="Lifecycle state of the workplace. Only ACTIVE workplaces accept work; ARCHIVED hides it from operational use."
                  example="ACTIVE"
                />
              </>
            }
          >
            <Select
              ariaLabel="Status"
              value={status}
              onChange={(v) => setStatus(v)}
              options={['ACTIVE', 'INACTIVE', 'ARCHIVED'].map((s) => ({ value: s, label: s }))}
            />
          </Field>
        )}
      </div>
      <Field
        label={
          <>
            Supported operating modes{' '}
            <InfoTip
              text="Which task types the operator may perform here when an HU is presented. PICKING is always enabled; tick others to allow them."
              example="PICKING, PUTAWAY"
            />
          </>
        }
      >
        <ModeCheckboxes value={modes} onChange={setModes} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Nodes panel for the selected station (STOCK + ORDER nodes)
// =========================================================================

function NodesPanel({ station, onChanged }: { station: Station; onChanged: () => void }) {
  const [editing, setEditing] = useState<StationNode | 'new' | null>(null)
  const [deleting, setDeleting] = useState<StationNode | null>(null)
  const [modesOpen, setModesOpen] = useState(false)

  const nodes = [...station.nodes].sort((a, b) => a.position - b.position)

  return (
    <>
      <div className="glass card-pad gtp-panel">
        <div className="toolbar">
          <strong>
            Nodes — {station.code}
            {station.name ? ` (${station.name})` : ''}
          </strong>
          <div className="spacer" />
          <button className="btn btn-ghost btn-sm" onClick={() => setModesOpen(true)}>
            Operating modes
          </button>
          <button className="btn btn-primary btn-sm" onClick={() => setEditing('new')}>
            + New node
          </button>
        </div>
        <p className="muted" style={{ fontSize: '.8rem', marginTop: 0 }}>
          STOCK nodes present a stock HU to the operator; ORDER nodes are order destinations (a fixed/conveyor location
          in ORDER_LOCATION mode, or a put-wall cubby in PUT_WALL mode) and carry an optional put-light.
        </p>
        <table>
          <thead>
            <tr>
              <th>Pos</th>
              <th>Role</th>
              <th>Code</th>
              <th>Put-light id</th>
              <th>Location id</th>
              <th>Order HU id</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {nodes.length === 0 ? (
              <Empty text="No nodes configured. Add STOCK and ORDER nodes." />
            ) : (
              nodes.map((n) => (
                <tr key={n.id}>
                  <td>{n.position}</td>
                  <td>
                    <RoleBadge role={n.role} />
                  </td>
                  <td>{n.code}</td>
                  <td>{n.putLightId || '—'}</td>
                  <td className="gtp-mono">{n.locationId || '—'}</td>
                  <td className="gtp-mono">{n.orderHuId || '—'}</td>
                  <td>
                    <StatusBadge status={n.status} />
                  </td>
                  <td className="gtp-row-actions">
                    <button className="btn btn-ghost btn-sm" onClick={() => setEditing(n)}>
                      Edit
                    </button>
                    <button className="btn btn-danger btn-sm" onClick={() => setDeleting(n)}>
                      Remove
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {editing && (
        <NodeDialog
          stationId={station.id}
          initial={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={onChanged}
        />
      )}
      {deleting && (
        <ConfirmDelete
          title="Remove node"
          confirmLabel="Remove"
          message={
            <>
              Remove {deleting.role} node <strong>{deleting.code}</strong>?
            </>
          }
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await deleteNode(deleting.id)
            onChanged()
          }}
        />
      )}
      {modesOpen && (
        <OperatingModesDialog station={station} onClose={() => setModesOpen(false)} onSaved={onChanged} />
      )}
    </>
  )
}

function NodeDialog({
  stationId,
  initial,
  onClose,
  onSaved,
}: {
  stationId: string
  initial: StationNode | null
  onClose: () => void
  onSaved: () => void
}) {
  const [role, setRole] = useState<NodeRole>(initial?.role ?? 'STOCK')
  const [code, setCode] = useState(initial?.code ?? '')
  const [putLightId, setPutLightId] = useState(initial?.putLightId ?? '')
  const [locationId, setLocationId] = useState(initial?.locationId ?? '')
  const [orderHuId, setOrderHuId] = useState(initial?.orderHuId ?? '')
  const [position, setPosition] = useState(String(initial?.position ?? 0))
  const [status, setStatus] = useState(initial?.status ?? 'ACTIVE')

  const isOrder = role === 'ORDER'
  const valid = code.trim() !== ''

  return (
    <EditDialog
      title={initial ? 'Edit node' : 'New node'}
      canSave={valid}
      onClose={onClose}
      onSave={async () => {
        const body: NodeBody = {
          role,
          code: code.trim(),
          putLightId: isOrder ? putLightId.trim() || null : null,
          locationId: uuidOrNull(locationId),
          orderHuId: isOrder ? uuidOrNull(orderHuId) : null,
          position: Number.isNaN(Number(position)) ? 0 : Number(position),
          status,
        }
        if (initial) await updateNode(initial.id, body)
        else await addNode(stationId, body)
        onSaved()
      }}
    >
      <div className="gtp-grid-2">
        <Field
          label={
            <>
              Role{' '}
              <InfoTip
                text="STOCK node presents a source stock HU to the operator; ORDER node is an order destination (fixed location or put-wall cubby)."
                example="ORDER"
              />
            </>
          }
          required
        >
          <Select
            ariaLabel="Role"
            value={role}
            onChange={(v) => setRole(v as NodeRole)}
            options={NODE_ROLES.map((r) => ({ value: r, label: r }))}
          />
        </Field>
        <Field
          label={
            <>
              Code{' '}
              <InfoTip
                text="Short unique identifier for this node within the workplace. Shown to the operator and used to address the position."
                example="ORD-A"
              />
            </>
          }
          required
        >
          <input className="form-control" value={code} onChange={(e) => setCode(e.target.value)} />
        </Field>
      </div>
      {isOrder && (
        <div className="gtp-grid-2">
          <Field
            label={
              <>
                Put-light id{' '}
                <InfoTip
                  text="Identifier of the physical pick/put-to-light or display device at this destination, used to guide the operator. Leave blank if none."
                  example="PTL-0307"
                />
              </>
            }
          >
            <input
              className="form-control"
              value={putLightId}
              placeholder="Physical light/display id"
              onChange={(e) => setPutLightId(e.target.value)}
            />
          </Field>
          <Field
            label={
              <>
                Order HU id{' '}
                <InfoTip
                  text="UUID of the order handling unit (carton/tote) currently bound to this destination. Usually set by the system; leave blank if none."
                  example="3f1c2a90-7e1b-4d6a-9c2f-2b8f0a1d4e57"
                />
              </>
            }
          >
            <input
              className="form-control gtp-mono"
              value={orderHuId}
              placeholder="UUID (currently bound order HU)"
              onChange={(e) => setOrderHuId(e.target.value)}
            />
          </Field>
        </div>
      )}
      <div className="gtp-grid-2">
        <Field
          label={
            <>
              Location id (master-data){' '}
              <InfoTip
                text="UUID of the master-data location this node maps to, when it is a fixed/conveyor position. Leave blank for dynamic put-wall cubbies."
                example="9a4b1d22-0c3e-4f88-b1aa-77e2c5d9f013"
              />
            </>
          }
        >
          <input
            className="form-control gtp-mono"
            value={locationId}
            placeholder="UUID, when mapped to a fixed location"
            onChange={(e) => setLocationId(e.target.value)}
          />
        </Field>
        <Field
          label={
            <>
              Position{' '}
              <InfoTip
                text="Ordering index that determines where this node appears in the workplace layout and node list (lower numbers first)."
                example="1"
              />
            </>
          }
        >
          <input
            className="form-control"
            type="number"
            value={position}
            onChange={(e) => setPosition(e.target.value)}
          />
        </Field>
      </div>
      <Field
        label={
          <>
            Status{' '}
            <InfoTip
              text="Whether this node is in operational use. INACTIVE nodes are kept on the workplace but skipped during work."
              example="ACTIVE"
            />
          </>
        }
      >
        <Select
          ariaLabel="Status"
          value={status}
          onChange={(v) => setStatus(v)}
          options={['ACTIVE', 'INACTIVE'].map((s) => ({ value: s, label: s }))}
        />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Operating-modes config dialog (dedicated endpoint; always keeps PICKING)
// =========================================================================

function OperatingModesDialog({
  station,
  onClose,
  onSaved,
}: {
  station: Station
  onClose: () => void
  onSaved: () => void
}) {
  const [modes, setModes] = useState<OperatingMode[]>(station.supportedModes)
  return (
    <EditDialog
      title={`Operating modes — ${station.code}`}
      onClose={onClose}
      onSave={async () => {
        await setSupportedModes(station.id, [...new Set<OperatingMode>(['PICKING', ...modes])])
        onSaved()
      }}
    >
      <p className="muted" style={{ fontSize: '.85rem', marginTop: 0 }}>
        What the operator can do at this workplace when an HU is presented. PICKING is always available.
      </p>
      <ModeCheckboxes value={modes} onChange={setModes} />
    </EditDialog>
  )
}

// =========================================================================
// Scoped styles for layout pieces not covered by the global design system.
// =========================================================================

function Styles() {
  return (
    <style>{`
      .gtp-panel { margin-bottom: 1rem; }
      .gtp-row-selected { background: rgba(168, 230, 79, .08); }
      .gtp-row-actions { display: flex; gap: .4rem; justify-content: flex-end; white-space: nowrap; }
      .gtp-badges { display: inline-flex; flex-wrap: wrap; gap: .3rem; }
      .gtp-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .8rem; }
      .gtp-form { display: flex; flex-direction: column; gap: .85rem; }
      .gtp-field { display: flex; flex-direction: column; }
      .gtp-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: .85rem; }
      .gtp-checks { display: flex; flex-wrap: wrap; gap: 1rem; }
      .gtp-check { display: inline-flex; align-items: center; gap: .45rem; color: var(--text); font-size: .875rem; margin: 0; }
      .gtp-check input { width: auto; }
    `}</style>
  )
}
