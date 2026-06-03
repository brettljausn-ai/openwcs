import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Equipment,
  LabelTemplate,
  Location,
  Sku,
  StorageBlock,
  Warehouse,
  createEquipment,
  createLabelTemplate,
  createLocation,
  createSku,
  createStorageBlock,
  createWarehouse,
  deleteLabelTemplate,
  deleteLocation,
  deleteSku,
  deleteWarehouse,
  listEquipment,
  listLabelTemplates,
  listLocations,
  listSkus,
  listStorageBlocks,
  listWarehouses,
  updateEquipment,
  updateLabelTemplate,
  updateLocation,
  updateSku,
  updateStorageBlock,
  updateWarehouse,
} from './api'

// ---------------------------------------------------------------------------
// Master Data admin: CRUD for warehouses, SKUs, storage blocks, locations,
// equipment and label templates against the gateway /api/master-data/**.
// Storage-block, location and equipment lists are scoped to a warehouse.
// ---------------------------------------------------------------------------

type EntityKey = 'warehouses' | 'skus' | 'storage-blocks' | 'locations' | 'equipment' | 'label-templates'

const ENTITIES: { key: EntityKey; label: string; scoped: boolean }[] = [
  { key: 'warehouses', label: 'Warehouses', scoped: false },
  { key: 'skus', label: 'SKUs', scoped: false },
  { key: 'storage-blocks', label: 'Storage blocks', scoped: true },
  { key: 'locations', label: 'Locations', scoped: true },
  { key: 'equipment', label: 'Equipment', scoped: true },
  { key: 'label-templates', label: 'Label templates', scoped: false },
]

export default function MasterDataScreen() {
  const [entity, setEntity] = useState<EntityKey>('warehouses')
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [warehouseId, setWarehouseId] = useState('')
  const [whError, setWhError] = useState<string | null>(null)

  const loadWarehouses = useCallback(async () => {
    try {
      setWhError(null)
      const list = await listWarehouses()
      setWarehouses(list)
      setWarehouseId((cur) => cur || (list[0]?.id ?? ''))
    } catch (e) {
      setWhError(errMsg(e))
    }
  }, [])

  useEffect(() => {
    loadWarehouses()
  }, [loadWarehouses])

  const active = ENTITIES.find((e) => e.key === entity)!
  const warehouseName = (id?: string | null) => warehouses.find((w) => w.id === id)?.code ?? id ?? '—'

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Master data</span>
        <h1>Master data</h1>
        <p>
          Manage warehouses, SKUs, storage blocks, locations, equipment and label templates. Blocks, locations
          and equipment are scoped to the selected warehouse.
        </p>
      </div>

      <div className="md-tabs">
        {ENTITIES.map((e) => (
          <button
            key={e.key}
            className={`btn btn-sm ${entity === e.key ? 'btn-primary' : 'btn-ghost'}`}
            onClick={() => setEntity(e.key)}
          >
            {e.label}
          </button>
        ))}
      </div>

      {active.scoped && (
        <div className="toolbar">
          <label style={{ margin: 0 }}>Warehouse</label>
          <select
            className="form-control"
            style={{ maxWidth: 280 }}
            value={warehouseId}
            onChange={(e) => setWarehouseId(e.target.value)}
          >
            <option value="">Select a warehouse…</option>
            {warehouses.map((w) => (
              <option key={w.id} value={w.id}>
                {w.code} — {w.name}
              </option>
            ))}
          </select>
          {whError && <span className="muted">{whError}</span>}
        </div>
      )}

      {entity === 'warehouses' && <WarehousesTab onWarehousesChanged={loadWarehouses} />}
      {entity === 'skus' && <SkusTab />}
      {entity === 'storage-blocks' && <StorageBlocksTab warehouseId={warehouseId} warehouseName={warehouseName} />}
      {entity === 'locations' && (
        <LocationsTab warehouseId={warehouseId} warehouses={warehouses} warehouseName={warehouseName} />
      )}
      {entity === 'equipment' && <EquipmentTab warehouseId={warehouseId} warehouseName={warehouseName} />}
      {entity === 'label-templates' && <LabelTemplatesTab />}

      <Styles />
    </div>
  )
}

// =========================================================================
// Shared helpers / primitives
// =========================================================================

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function StatusBadge({ status }: { status?: string }) {
  const s = (status ?? '').toUpperCase()
  const cls = s === 'ACTIVE' ? 'badge-success' : s === 'ARCHIVED' ? 'badge-danger' : 'badge-warning'
  return <span className={`badge ${cls}`}>{status ?? '—'}</span>
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

function Field({
  label,
  children,
  required,
}: {
  label: string
  children: React.ReactNode
  required?: boolean
}) {
  return (
    <div className="md-field">
      <label>
        {label}
        {required && <span style={{ color: '#ff8a80' }}> *</span>}
      </label>
      {children}
    </div>
  )
}

function Toolbar({ onAdd, label, children }: { onAdd: () => void; label: string; children?: React.ReactNode }) {
  return (
    <div className="toolbar">
      {children}
      <div className="spacer" />
      <button className="btn btn-primary btn-sm" onClick={onAdd}>
        + New {label}
      </button>
    </div>
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

/** Generic edit/create dialog shell with an error slot and Save/Cancel actions. */
function EditDialog<T>({
  title,
  draft,
  onClose,
  onSave,
  children,
  canSave = true,
}: {
  title: string
  draft: T
  onClose: () => void
  onSave: (draft: T) => Promise<void>
  children: React.ReactNode
  canSave?: boolean
}) {
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  async function save() {
    setSaving(true)
    setError(null)
    try {
      await onSave(draft)
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
      <div className="md-form">{children}</div>
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
  name,
  onConfirm,
  onClose,
}: {
  name: string
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
    <Dialog title="Confirm delete" onClose={onClose}>
      {error && <div className="alert alert-danger">{error}</div>}
      <p>
        Archive <strong>{name}</strong>? It will be marked <code>ARCHIVED</code>.
      </p>
      <div className="dialog-actions">
        <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={busy}>
          Cancel
        </button>
        <button className="btn btn-danger btn-sm" onClick={go} disabled={busy}>
          {busy ? <span className="spin" /> : 'Archive'}
        </button>
      </div>
    </Dialog>
  )
}

function num(v: string): number | null {
  if (v.trim() === '') return null
  const n = Number(v)
  return Number.isNaN(n) ? null : n
}

const STATUS_OPTIONS = ['ACTIVE', 'INACTIVE', 'ARCHIVED']

function StatusSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  return (
    <select className="form-control" value={value} onChange={(e) => onChange(e.target.value)}>
      {STATUS_OPTIONS.map((s) => (
        <option key={s} value={s}>
          {s}
        </option>
      ))}
    </select>
  )
}

// =========================================================================
// Warehouses
// =========================================================================

function WarehousesTab({ onWarehousesChanged }: { onWarehousesChanged: () => void }) {
  const [rows, setRows] = useState<Warehouse[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Warehouse | null>(null)
  const [deleting, setDeleting] = useState<Warehouse | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setError(null)
      setRows(await listWarehouses())
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => {
    load()
  }, [load])

  const blank: Warehouse = { code: '', name: '', timezone: 'UTC', status: 'ACTIVE' }

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label="warehouse" onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Name</th>
            <th>Timezone</th>
            <th>Status</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <Empty text="Loading…" />
          ) : rows.length === 0 ? (
            <Empty text="No warehouses yet." />
          ) : (
            rows.map((w) => (
              <tr key={w.id}>
                <td>{w.code}</td>
                <td>{w.name}</td>
                <td>{w.timezone}</td>
                <td>
                  <StatusBadge status={w.status} />
                </td>
                <td className="md-row-actions">
                  <button className="btn btn-ghost btn-sm" onClick={() => setEditing(w)}>
                    Edit
                  </button>
                  <button className="btn btn-danger btn-sm" onClick={() => setDeleting(w)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>

      {editing && (
        <WarehouseDialog
          initial={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            load()
            onWarehousesChanged()
          }}
        />
      )}
      {deleting && (
        <ConfirmDelete
          name={deleting.code}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await deleteWarehouse(deleting.id!)
            await load()
            onWarehousesChanged()
          }}
        />
      )}
    </div>
  )
}

function WarehouseDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: Warehouse
  onClose: () => void
  onSaved: () => void
}) {
  const [d, setD] = useState<Warehouse>(initial)
  const valid = d.code.trim() !== '' && d.name.trim() !== '' && d.timezone.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? 'Edit warehouse' : 'New warehouse'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (w) => {
        if (w.id) await updateWarehouse(w.id, w)
        else await createWarehouse(w)
        onSaved()
      }}
    >
      <Field label="Code" required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <Field label="Name" required>
        <input className="form-control" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} />
      </Field>
      <Field label="Timezone" required>
        <input
          className="form-control"
          value={d.timezone}
          placeholder="e.g. Europe/Vienna"
          onChange={(e) => setD({ ...d, timezone: e.target.value })}
        />
      </Field>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// SKUs
// =========================================================================

function SkusTab() {
  const [rows, setRows] = useState<Sku[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [editing, setEditing] = useState<Sku | null>(null)
  const [deleting, setDeleting] = useState<Sku | null>(null)

  const load = useCallback(async (q?: string) => {
    setLoading(true)
    try {
      setError(null)
      setRows(await listSkus(q && q.trim() ? q.trim() : undefined))
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => {
    load()
  }, [load])

  const blank: Sku = {
    code: '',
    description: '',
    status: 'ACTIVE',
    ownerClient: '',
    batchTracked: false,
    serialTracked: false,
    dateTracked: false,
  }

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label="SKU" onAdd={() => setEditing(blank)}>
        <input
          className="form-control"
          style={{ maxWidth: 240 }}
          placeholder="Search code / description…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && load(search)}
        />
        <button className="btn btn-ghost btn-sm" onClick={() => load(search)}>
          Search
        </button>
      </Toolbar>
      {error && <div className="alert alert-danger">{error}</div>}
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Description</th>
            <th>Owner</th>
            <th>Tracking</th>
            <th>Status</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <Empty text="Loading…" />
          ) : rows.length === 0 ? (
            <Empty text="No SKUs found." />
          ) : (
            rows.map((s) => (
              <tr key={s.id}>
                <td>{s.code}</td>
                <td>{s.description || '—'}</td>
                <td>{s.ownerClient || '—'}</td>
                <td>
                  {[
                    s.batchTracked && 'Batch',
                    s.serialTracked && 'Serial',
                    s.dateTracked && 'Date',
                  ]
                    .filter(Boolean)
                    .join(', ') || '—'}
                </td>
                <td>
                  <StatusBadge status={s.status} />
                </td>
                <td className="md-row-actions">
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

      {editing && <SkuDialog initial={editing} onClose={() => setEditing(null)} onSaved={() => load(search)} />}
      {deleting && (
        <ConfirmDelete
          name={deleting.code}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await deleteSku(deleting.id!)
            await load(search)
          }}
        />
      )}
    </div>
  )
}

function SkuDialog({ initial, onClose, onSaved }: { initial: Sku; onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState<Sku>(initial)
  const valid = d.code.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? 'Edit SKU' : 'New SKU'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (s) => {
        if (s.id) await updateSku(s.id, s)
        else await createSku(s)
        onSaved()
      }}
    >
      <Field label="Code" required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <Field label="Description">
        <input
          className="form-control"
          value={d.description ?? ''}
          onChange={(e) => setD({ ...d, description: e.target.value })}
        />
      </Field>
      <Field label="Owner client">
        <input
          className="form-control"
          value={d.ownerClient ?? ''}
          onChange={(e) => setD({ ...d, ownerClient: e.target.value })}
        />
      </Field>
      <div className="md-checks">
        <label className="md-check">
          <input
            type="checkbox"
            checked={d.batchTracked}
            onChange={(e) => setD({ ...d, batchTracked: e.target.checked })}
          />
          Batch tracked
        </label>
        <label className="md-check">
          <input
            type="checkbox"
            checked={d.serialTracked}
            onChange={(e) => setD({ ...d, serialTracked: e.target.checked })}
          />
          Serial tracked
        </label>
        <label className="md-check">
          <input
            type="checkbox"
            checked={d.dateTracked}
            onChange={(e) => setD({ ...d, dateTracked: e.target.checked })}
          />
          Date tracked
        </label>
      </div>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Storage blocks (warehouse-scoped)
// =========================================================================

const STORAGE_TYPES = ['SHUTTLE_ASRS', 'CRANE_ASRS', 'AUTOSTORE', 'AMR_GTP', 'MANUAL_PICK', 'RESERVE_RACK']
const GRANULARITIES = ['BLOCK', 'LOCATION']

function ScopeNotice({ warehouseId }: { warehouseId: string }) {
  if (warehouseId) return null
  return <div className="alert">Select a warehouse above to manage this entity.</div>
}

function StorageBlocksTab({
  warehouseId,
  warehouseName,
}: {
  warehouseId: string
  warehouseName: (id?: string | null) => string
}) {
  const [rows, setRows] = useState<StorageBlock[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<StorageBlock | null>(null)

  const load = useCallback(async () => {
    if (!warehouseId) {
      setRows([])
      return
    }
    setLoading(true)
    try {
      setError(null)
      setRows(await listStorageBlocks(warehouseId))
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])
  useEffect(() => {
    load()
  }, [load])

  const blank: StorageBlock = {
    warehouseId,
    code: '',
    storageType: STORAGE_TYPES[0],
    slottingGranularity: 'BLOCK',
    gtp: false,
    allowedHuTypes: [],
    status: 'ACTIVE',
  }

  if (!warehouseId) return <ScopeNotice warehouseId={warehouseId} />

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label="storage block" onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Storage type</th>
            <th>Granularity</th>
            <th>GTP</th>
            <th>Allowed HU types</th>
            <th>Status</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <Empty text="Loading…" />
          ) : rows.length === 0 ? (
            <Empty text="No storage blocks for this warehouse." />
          ) : (
            rows.map((b) => (
              <tr key={b.id}>
                <td>{b.code}</td>
                <td>{b.storageType}</td>
                <td>{b.slottingGranularity}</td>
                <td>{b.gtp ? 'Yes' : 'No'}</td>
                <td>{b.allowedHuTypes && b.allowedHuTypes.length ? b.allowedHuTypes.join(', ') : 'Any'}</td>
                <td>
                  <StatusBadge status={b.status} />
                </td>
                <td className="md-row-actions">
                  <button className="btn btn-ghost btn-sm" onClick={() => setEditing(b)}>
                    Edit
                  </button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
      <p className="muted" style={{ fontSize: '.8rem', marginTop: '.75rem' }}>
        Warehouse: {warehouseName(warehouseId)}. Storage blocks have no archive endpoint; set status to ARCHIVED via
        Edit.
      </p>

      {editing && (
        <StorageBlockDialog
          initial={{ ...editing, warehouseId }}
          onClose={() => setEditing(null)}
          onSaved={load}
        />
      )}
    </div>
  )
}

function StorageBlockDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: StorageBlock
  onClose: () => void
  onSaved: () => void
}) {
  const [d, setD] = useState<StorageBlock>(initial)
  const [huText, setHuText] = useState((initial.allowedHuTypes ?? []).join(', '))
  const valid = d.code.trim() !== '' && d.storageType.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? 'Edit storage block' : 'New storage block'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async () => {
        const huTypes = huText
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean)
        const body = { ...d, allowedHuTypes: huTypes }
        if (body.id) await updateStorageBlock(body.id, body)
        else await createStorageBlock(body)
        onSaved()
      }}
    >
      <Field label="Code" required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <Field label="Storage type" required>
        <select
          className="form-control"
          value={d.storageType}
          onChange={(e) => setD({ ...d, storageType: e.target.value })}
        >
          {STORAGE_TYPES.map((t) => (
            <option key={t} value={t}>
              {t}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Slotting granularity">
        <select
          className="form-control"
          value={d.slottingGranularity}
          onChange={(e) => setD({ ...d, slottingGranularity: e.target.value })}
        >
          {GRANULARITIES.map((g) => (
            <option key={g} value={g}>
              {g}
            </option>
          ))}
        </select>
      </Field>
      <label className="md-check">
        <input type="checkbox" checked={d.gtp} onChange={(e) => setD({ ...d, gtp: e.target.checked })} />
        Goods-to-person (GTP)
      </label>
      <Field label="Allowed HU types (comma-separated, blank = any)">
        <input className="form-control" value={huText} onChange={(e) => setHuText(e.target.value)} />
      </Field>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Locations (warehouse-scoped)
// =========================================================================

const LOCATION_TYPES = [
  'BIN',
  'PALLET',
  'FREE_SPACE',
  'SHELF',
  'GRID_BIN',
  'ASRS_SLOT',
  'CONVEYOR_SEGMENT',
  'ROBOT_PORT',
  'STATION',
]
const PURPOSES = [
  'STORAGE',
  'TRANSPORT',
  'STAGING',
  'PICK',
  'PACK',
  'INDUCT',
  'RECEIVING',
  'SHIPPING',
  'QUARANTINE',
  'RETURNS',
]

function LocationsTab({
  warehouseId,
  warehouseName,
}: {
  warehouseId: string
  warehouses: Warehouse[]
  warehouseName: (id?: string | null) => string
}) {
  const [rows, setRows] = useState<Location[]>([])
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Location | null>(null)
  const [deleting, setDeleting] = useState<Location | null>(null)

  const load = useCallback(async () => {
    if (!warehouseId) {
      setRows([])
      return
    }
    setLoading(true)
    try {
      setError(null)
      const [locs, blks] = await Promise.all([listLocations(warehouseId), listStorageBlocks(warehouseId)])
      setRows(locs)
      setBlocks(blks)
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])
  useEffect(() => {
    load()
  }, [load])

  const blockCode = (id?: string | null) => blocks.find((b) => b.id === id)?.code ?? (id ? '—' : '—')

  const blank: Location = {
    warehouseId,
    code: '',
    locationType: LOCATION_TYPES[0],
    purpose: PURPOSES[0],
    status: 'ACTIVE',
    mixedAllowed: true,
    laneDepth: 1,
    blockId: null,
  }

  if (!warehouseId) return <ScopeNotice warehouseId={warehouseId} />

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label="location" onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <div className="md-scroll-x">
        <table>
          <thead>
            <tr>
              <th>Code</th>
              <th>Type</th>
              <th>Purpose</th>
              <th>Block</th>
              <th>Aisle</th>
              <th>Side</th>
              <th>X/Y/Z</th>
              <th>Dist→exit</th>
              <th>Status</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <Empty text="Loading…" />
            ) : rows.length === 0 ? (
              <Empty text="No locations for this warehouse." />
            ) : (
              rows.map((l) => (
                <tr key={l.id}>
                  <td>{l.code}</td>
                  <td>{l.locationType}</td>
                  <td>{l.purpose}</td>
                  <td>{blockCode(l.blockId)}</td>
                  <td>{l.aisle || '—'}</td>
                  <td>{l.side || '—'}</td>
                  <td>
                    {[l.posX, l.posY, l.posZ].map((v) => (v ?? '·')).join('/')}
                  </td>
                  <td>{l.distanceToExit ?? '—'}</td>
                  <td>
                    <StatusBadge status={l.status} />
                  </td>
                  <td className="md-row-actions">
                    <button className="btn btn-ghost btn-sm" onClick={() => setEditing(l)}>
                      Edit
                    </button>
                    <button className="btn btn-danger btn-sm" onClick={() => setDeleting(l)}>
                      Delete
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
      <p className="muted" style={{ fontSize: '.8rem', marginTop: '.75rem' }}>
        Warehouse: {warehouseName(warehouseId)}.
      </p>

      {editing && (
        <LocationDialog
          initial={{ ...editing, warehouseId }}
          blocks={blocks}
          onClose={() => setEditing(null)}
          onSaved={load}
        />
      )}
      {deleting && (
        <ConfirmDelete
          name={deleting.code}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await deleteLocation(deleting.id!)
            await load()
          }}
        />
      )}
    </div>
  )
}

function LocationDialog({
  initial,
  blocks,
  onClose,
  onSaved,
}: {
  initial: Location
  blocks: StorageBlock[]
  onClose: () => void
  onSaved: () => void
}) {
  const [d, setD] = useState<Location>(initial)
  const valid = d.code.trim() !== '' && d.locationType.trim() !== '' && d.purpose.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? 'Edit location' : 'New location'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (l) => {
        if (l.id) await updateLocation(l.id, l)
        else await createLocation(l)
        onSaved()
      }}
    >
      <Field label="Code" required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <div className="md-grid-2">
        <Field label="Location type" required>
          <select
            className="form-control"
            value={d.locationType}
            onChange={(e) => setD({ ...d, locationType: e.target.value })}
          >
            {LOCATION_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Purpose" required>
          <select
            className="form-control"
            value={d.purpose}
            onChange={(e) => setD({ ...d, purpose: e.target.value })}
          >
            {PURPOSES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <Field label="Storage block">
        <select
          className="form-control"
          value={d.blockId ?? ''}
          onChange={(e) => setD({ ...d, blockId: e.target.value || null })}
        >
          <option value="">— None —</option>
          {blocks.map((b) => (
            <option key={b.id} value={b.id}>
              {b.code} ({b.storageType})
            </option>
          ))}
        </select>
      </Field>
      <div className="md-grid-2">
        <Field label="Aisle">
          <input
            className="form-control"
            value={d.aisle ?? ''}
            onChange={(e) => setD({ ...d, aisle: e.target.value || null })}
          />
        </Field>
        <Field label="Side">
          <input
            className="form-control"
            value={d.side ?? ''}
            placeholder="LEFT / RIGHT"
            onChange={(e) => setD({ ...d, side: e.target.value || null })}
          />
        </Field>
      </div>
      <div className="md-grid-3">
        <Field label="Pos X">
          <input
            className="form-control"
            type="number"
            value={d.posX ?? ''}
            onChange={(e) => setD({ ...d, posX: num(e.target.value) })}
          />
        </Field>
        <Field label="Pos Y">
          <input
            className="form-control"
            type="number"
            value={d.posY ?? ''}
            onChange={(e) => setD({ ...d, posY: num(e.target.value) })}
          />
        </Field>
        <Field label="Pos Z">
          <input
            className="form-control"
            type="number"
            value={d.posZ ?? ''}
            onChange={(e) => setD({ ...d, posZ: num(e.target.value) })}
          />
        </Field>
      </div>
      <div className="md-grid-2">
        <Field label="Distance to exit">
          <input
            className="form-control"
            type="number"
            value={d.distanceToExit ?? ''}
            onChange={(e) => setD({ ...d, distanceToExit: num(e.target.value) })}
          />
        </Field>
        <Field label="Lane depth">
          <input
            className="form-control"
            type="number"
            value={d.laneDepth}
            onChange={(e) => setD({ ...d, laneDepth: num(e.target.value) ?? 1 })}
          />
        </Field>
      </div>
      <label className="md-check">
        <input
          type="checkbox"
          checked={d.mixedAllowed}
          onChange={(e) => setD({ ...d, mixedAllowed: e.target.checked })}
        />
        Mixed SKUs allowed
      </label>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Equipment (warehouse-scoped)
// =========================================================================

const EQUIPMENT_FAMILIES = ['CONVEYOR', 'ASRS', 'AMR', 'AUTOSTORE']

function EquipmentTab({
  warehouseId,
  warehouseName,
}: {
  warehouseId: string
  warehouseName: (id?: string | null) => string
}) {
  const [rows, setRows] = useState<Equipment[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Equipment | null>(null)

  const load = useCallback(async () => {
    if (!warehouseId) {
      setRows([])
      return
    }
    setLoading(true)
    try {
      setError(null)
      setRows(await listEquipment(warehouseId))
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])
  useEffect(() => {
    load()
  }, [load])

  const blank: Equipment = {
    warehouseId,
    family: EQUIPMENT_FAMILIES[0],
    vendor: '',
    model: '',
    adapterEndpoint: '',
    status: 'ACTIVE',
  }

  if (!warehouseId) return <ScopeNotice warehouseId={warehouseId} />

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label="equipment" onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <table>
        <thead>
          <tr>
            <th>Family</th>
            <th>Vendor</th>
            <th>Model</th>
            <th>Adapter endpoint</th>
            <th>Status</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <Empty text="Loading…" />
          ) : rows.length === 0 ? (
            <Empty text="No equipment for this warehouse." />
          ) : (
            rows.map((e) => (
              <tr key={e.id}>
                <td>{e.family}</td>
                <td>{e.vendor || '—'}</td>
                <td>{e.model || '—'}</td>
                <td>{e.adapterEndpoint || '—'}</td>
                <td>
                  <StatusBadge status={e.status} />
                </td>
                <td className="md-row-actions">
                  <button className="btn btn-ghost btn-sm" onClick={() => setEditing(e)}>
                    Edit
                  </button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
      <p className="muted" style={{ fontSize: '.8rem', marginTop: '.75rem' }}>
        Warehouse: {warehouseName(warehouseId)}. Equipment has no archive endpoint; set status to ARCHIVED via Edit.
      </p>

      {editing && (
        <EquipmentDialog initial={{ ...editing, warehouseId }} onClose={() => setEditing(null)} onSaved={load} />
      )}
    </div>
  )
}

function EquipmentDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: Equipment
  onClose: () => void
  onSaved: () => void
}) {
  const [d, setD] = useState<Equipment>(initial)
  const valid = d.family.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? 'Edit equipment' : 'New equipment'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (e) => {
        if (e.id) await updateEquipment(e.id, e)
        else await createEquipment(e)
        onSaved()
      }}
    >
      <Field label="Family" required>
        <select className="form-control" value={d.family} onChange={(e) => setD({ ...d, family: e.target.value })}>
          {EQUIPMENT_FAMILIES.map((f) => (
            <option key={f} value={f}>
              {f}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Vendor">
        <input
          className="form-control"
          value={d.vendor ?? ''}
          onChange={(e) => setD({ ...d, vendor: e.target.value })}
        />
      </Field>
      <Field label="Model">
        <input
          className="form-control"
          value={d.model ?? ''}
          onChange={(e) => setD({ ...d, model: e.target.value })}
        />
      </Field>
      <Field label="Adapter endpoint">
        <input
          className="form-control"
          value={d.adapterEndpoint ?? ''}
          placeholder="e.g. http://device-adapter:8080"
          onChange={(e) => setD({ ...d, adapterEndpoint: e.target.value })}
        />
      </Field>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Label templates (global)
// =========================================================================

function LabelTemplatesTab() {
  const [rows, setRows] = useState<LabelTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<LabelTemplate | null>(null)
  const [deleting, setDeleting] = useState<LabelTemplate | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setError(null)
      setRows(await listLabelTemplates())
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => {
    load()
  }, [load])

  const blank: LabelTemplate = {
    code: '',
    name: '',
    widthMm: 100,
    heightMm: 150,
    dpi: 203,
    elements: [],
    status: 'ACTIVE',
  }

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label="template" onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Name</th>
            <th>Size (mm)</th>
            <th>DPI</th>
            <th>Elements</th>
            <th>Status</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {loading ? (
            <Empty text="Loading…" />
          ) : rows.length === 0 ? (
            <Empty text="No label templates yet." />
          ) : (
            rows.map((t) => (
              <tr key={t.id}>
                <td>{t.code}</td>
                <td>{t.name || '—'}</td>
                <td>
                  {t.widthMm} × {t.heightMm}
                </td>
                <td>{t.dpi}</td>
                <td>{t.elements?.length ?? 0}</td>
                <td>
                  <StatusBadge status={t.status} />
                </td>
                <td className="md-row-actions">
                  <button className="btn btn-ghost btn-sm" onClick={() => setEditing(t)}>
                    Edit
                  </button>
                  <button className="btn btn-danger btn-sm" onClick={() => setDeleting(t)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>

      {editing && (
        <LabelTemplateDialog initial={editing} onClose={() => setEditing(null)} onSaved={load} />
      )}
      {deleting && (
        <ConfirmDelete
          name={deleting.code}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await deleteLabelTemplate(deleting.id!)
            await load()
          }}
        />
      )}
    </div>
  )
}

function LabelTemplateDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: LabelTemplate
  onClose: () => void
  onSaved: () => void
}) {
  const [d, setD] = useState<LabelTemplate>(initial)
  const valid = d.code.trim() !== '' && d.widthMm > 0 && d.heightMm > 0 && d.dpi > 0
  return (
    <EditDialog
      title={initial.id ? 'Edit label template' : 'New label template'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (t) => {
        if (t.id) await updateLabelTemplate(t.id, t)
        else await createLabelTemplate(t)
        onSaved()
      }}
    >
      <Field label="Code" required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <Field label="Name">
        <input
          className="form-control"
          value={d.name ?? ''}
          onChange={(e) => setD({ ...d, name: e.target.value })}
        />
      </Field>
      <div className="md-grid-3">
        <Field label="Width (mm)" required>
          <input
            className="form-control"
            type="number"
            value={d.widthMm}
            onChange={(e) => setD({ ...d, widthMm: num(e.target.value) ?? 0 })}
          />
        </Field>
        <Field label="Height (mm)" required>
          <input
            className="form-control"
            type="number"
            value={d.heightMm}
            onChange={(e) => setD({ ...d, heightMm: num(e.target.value) ?? 0 })}
          />
        </Field>
        <Field label="DPI" required>
          <input
            className="form-control"
            type="number"
            value={d.dpi}
            onChange={(e) => setD({ ...d, dpi: num(e.target.value) ?? 203 })}
          />
        </Field>
      </div>
      <p className="muted" style={{ fontSize: '.8rem' }}>
        {d.elements?.length ?? 0} element(s). The visual element designer lives in a future iteration; element data is
        preserved on save.
      </p>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Scoped styles for layout pieces not covered by the global design system.
// =========================================================================

function Styles() {
  return (
    <style>{`
      .md-tabs { display: flex; flex-wrap: wrap; gap: .5rem; margin-bottom: 1.25rem; }
      .md-panel { margin-bottom: 1rem; }
      .md-row-actions { display: flex; gap: .4rem; justify-content: flex-end; white-space: nowrap; }
      .md-scroll-x { overflow-x: auto; }
      .md-form { display: flex; flex-direction: column; gap: .85rem; }
      .md-field { display: flex; flex-direction: column; }
      .md-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: .85rem; }
      .md-grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: .85rem; }
      .md-checks { display: flex; flex-wrap: wrap; gap: 1rem; }
      .md-check { display: inline-flex; align-items: center; gap: .45rem; color: var(--text); font-size: .875rem; margin: 0; }
      .md-check input { width: auto; }
    `}</style>
  )
}
