import { useCallback, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useParams } from 'react-router-dom'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useAuth } from '../auth/AuthContext'
import Select from '../ui/Select'
import DataTable from '../ui/DataTable'
import { countActiveHandlingUnits } from '../inventory/api'
import {
  Barcode,
  Equipment,
  HandlingUnitType,
  LabelTemplate,
  Location,
  Sku,
  StorageBlock,
  UnitOfMeasure,
  Warehouse,
  archiveHandlingUnitType,
  bulkCreateLocations,
  createEquipment,
  createHandlingUnitType,
  createLabelTemplate,
  createLocation,
  createStorageBlock,
  createWarehouse,
  deleteLabelTemplate,
  deleteLocation,
  deleteWarehouse,
  listEquipment,
  listHandlingUnitTypes,
  listLabelTemplates,
  listLocations,
  listSkuBarcodes,
  listSkuUoms,
  listSkus,
  listStorageBlocks,
  listWarehouses,
  restoreHandlingUnitType,
  updateEquipment,
  updateHandlingUnitType,
  updateLabelTemplate,
  updateLocation,
  updateStorageBlock,
  updateWarehouse,
} from './api'

// ---------------------------------------------------------------------------
// Master Data admin: CRUD for warehouses, SKUs, storage blocks, locations,
// equipment and label templates against the gateway /api/master-data/**.
// Storage-block, location and equipment lists are scoped to a warehouse.
// ---------------------------------------------------------------------------

type EntityKey =
  | 'warehouses'
  | 'skus'
  | 'storage-blocks'
  | 'locations'
  | 'equipment'
  | 'handling-unit-types'
  | 'label-templates'

const ENTITIES: { key: EntityKey; label: string; scoped: boolean }[] = [
  { key: 'warehouses', label: 'Warehouses', scoped: false },
  { key: 'skus', label: 'SKUs', scoped: false },
  { key: 'storage-blocks', label: 'Storage blocks', scoped: true },
  { key: 'locations', label: 'Locations', scoped: true },
  { key: 'equipment', label: 'Equipment', scoped: true },
  { key: 'handling-unit-types', label: 'Handling unit types', scoped: false },
  { key: 'label-templates', label: 'Label templates', scoped: false },
]

export default function MasterDataScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  // The active entity is the URL sub-page (/master-data/:entity); the sidebar submenu drives it.
  const { entity: entityParam } = useParams()
  const entity: EntityKey = ENTITIES.find((e) => e.key === entityParam)?.key ?? 'warehouses'
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [whError, setWhError] = useState<string | null>(null)

  const loadWarehouses = useCallback(async () => {
    try {
      setWhError(null)
      setWarehouses(await listWarehouses())
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
        <h1>{active.label}</h1>
        <p>
          {active.scoped
            ? 'Scoped to the warehouse selected in the top bar.'
            : entity === 'skus'
              ? 'SKUs, with their units of measure and barcodes, are owned by the host system and shown read-only.'
              : 'Manage this master-data catalog.'}
        </p>
      </div>

      {active.scoped && whError && (
        <div className="toolbar">
          <span className="muted">{whError}</span>
        </div>
      )}

      {entity === 'warehouses' && <WarehousesTab onWarehousesChanged={loadWarehouses} />}
      {entity === 'skus' && <SkusTab />}
      {entity === 'storage-blocks' && <StorageBlocksTab warehouseId={warehouseId} warehouseName={warehouseName} />}
      {entity === 'locations' && (
        <LocationsTab warehouseId={warehouseId} warehouses={warehouses} warehouseName={warehouseName} />
      )}
      {entity === 'equipment' && <EquipmentTab warehouseId={warehouseId} warehouseName={warehouseName} />}
      {entity === 'handling-unit-types' && <HandlingUnitTypesTab />}
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

function Dialog({
  title,
  children,
  onClose,
  size,
}: {
  title: string
  children: React.ReactNode
  onClose: () => void
  size?: 'lg'
}) {
  // Portal to <body> so the fixed backdrop isn't trapped inside a card's backdrop-filter
  // containing block (which left the dialog off-centre inside the table card).
  return createPortal(
    <div className="modal-backdrop" onClick={onClose}>
      <div className={`dialog glass${size === 'lg' ? ' dialog-lg' : ''}`} onClick={(e) => e.stopPropagation()}>
        <h2>{title}</h2>
        {children}
      </div>
    </div>,
    document.body,
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
  wide = false,
}: {
  title: string
  draft: T
  onClose: () => void
  onSave: (draft: T) => Promise<void>
  children: React.ReactNode
  canSave?: boolean
  wide?: boolean
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
    <Dialog title={title} onClose={onClose} size={wide ? 'lg' : undefined}>
      {error && <div className="alert alert-danger">{error}</div>}
      <div className={`md-form${wide ? ' md-form-2col' : ''}`}>{children}</div>
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
    <Select
      value={value}
      onChange={onChange}
      options={STATUS_OPTIONS.map((s) => ({ value: s, label: s }))}
      ariaLabel="Status"
    />
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
      <DataTable
        rows={rows}
        rowKey={(w) => w.id ?? w.code}
        search={(w) => `${w.code} ${w.name} ${w.timezone} ${w.status}`}
        searchPlaceholder="Search warehouses…"
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? 'Loading…' : 'No warehouses yet.'}
        columns={[
          { key: 'code', header: 'Code', sortable: true, sortValue: (w) => w.code ?? '', render: (w) => w.code },
          { key: 'name', header: 'Name', sortable: true, sortValue: (w) => w.name ?? '', render: (w) => w.name },
          { key: 'timezone', header: 'Timezone', sortable: true, sortValue: (w) => w.timezone ?? '', render: (w) => w.timezone },
          { key: 'status', header: 'Status', sortable: true, sortValue: (w) => w.status ?? '', render: (w) => <StatusBadge status={w.status} /> },
          {
            key: 'actions',
            header: '',
            render: (w) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(w)}>
                  Edit
                </button>
                <button className="btn btn-danger btn-sm" onClick={() => setDeleting(w)}>
                  Delete
                </button>
              </div>
            ),
          },
        ]}
      />

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

// Simple UTC offsets (UTC-12 … UTC+14) — easier than the full IANA list.
const TZ_OPTIONS = (() => {
  const zones: string[] = []
  for (let h = -12; h <= 14; h++) zones.push(h === 0 ? 'UTC' : `UTC${h > 0 ? '+' : ''}${h}`)
  return zones.map((z) => ({ value: z, label: z }))
})()

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
      wide
      onClose={onClose}
      onSave={async (w) => {
        if (w.id) await updateWarehouse(w.id, w)
        else await createWarehouse(w)
        onSaved()
      }}
    >
      <Field label="Code" required>
        <input
          className="form-control"
          value={d.code}
          readOnly={!!initial.id}
          title={initial.id ? 'Code is fixed once set' : undefined}
          onChange={(e) => setD({ ...d, code: e.target.value })}
        />
      </Field>
      <Field label="Name" required>
        <input className="form-control" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} />
      </Field>
      <Field label="Timezone" required>
        <Select ariaLabel="Timezone" value={d.timezone} onChange={(v) => setD({ ...d, timezone: v })} options={TZ_OPTIONS} />
      </Field>
      <Field label="Address line 1">
        <input className="form-control" value={d.addressLine1 ?? ''} onChange={(e) => setD({ ...d, addressLine1: e.target.value })} />
      </Field>
      <Field label="Address line 2">
        <input className="form-control" value={d.addressLine2 ?? ''} onChange={(e) => setD({ ...d, addressLine2: e.target.value })} />
      </Field>
      <Field label="City">
        <input className="form-control" value={d.city ?? ''} onChange={(e) => setD({ ...d, city: e.target.value })} />
      </Field>
      <Field label="Region / State">
        <input className="form-control" value={d.region ?? ''} onChange={(e) => setD({ ...d, region: e.target.value })} />
      </Field>
      <Field label="Postal code">
        <input className="form-control" value={d.postalCode ?? ''} onChange={(e) => setD({ ...d, postalCode: e.target.value })} />
      </Field>
      <Field label="Country">
        <input className="form-control" value={d.country ?? ''} onChange={(e) => setD({ ...d, country: e.target.value })} />
      </Field>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// SKUs (host-owned: read-only)
//
// SKUs, their units of measure and their barcodes are master data owned by the
// host/ERP and pushed into the WCS via host sync (build.md §6, §16). The WCS is a
// slave to them: list + search + a read-only detail only — no create/edit/delete.
// =========================================================================

function HostManagedNote() {
  return (
    <div className="alert md-host-note">
      <span className="badge badge-warning">Host-managed</span>
      Managed by the host system — read-only in the WCS. SKUs, units of measure and barcodes are kept in sync from the
      host and cannot be created, edited or deleted here.
    </div>
  )
}

function SkusTab() {
  const [rows, setRows] = useState<Sku[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [viewing, setViewing] = useState<Sku | null>(null)

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

  return (
    <div className="glass card-pad md-panel">
      <HostManagedNote />
      <div className="toolbar">
        <input
          className="form-control"
          style={{ maxWidth: 240 }}
          placeholder="Search code / description / barcode…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && load(search)}
        />
        <button className="btn btn-ghost btn-sm" onClick={() => load(search)}>
          Search
        </button>
        <div className="spacer" />
      </div>
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={rows}
        rowKey={(s) => s.id ?? s.code}
        search={(s) => `${s.code} ${s.description ?? ''} ${s.ownerClient ?? ''} ${s.status ?? ''}`}
        searchPlaceholder="Filter loaded SKUs…"
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? 'Loading…' : 'No SKUs found.'}
        columns={[
          { key: 'code', header: 'Code', sortable: true, sortValue: (s) => s.code ?? '', render: (s) => s.code },
          {
            key: 'description',
            header: 'Description',
            sortable: true,
            sortValue: (s) => s.description ?? '',
            render: (s) => s.description || '—',
          },
          {
            key: 'ownerClient',
            header: 'Owner',
            sortable: true,
            sortValue: (s) => s.ownerClient ?? '',
            render: (s) => s.ownerClient || '—',
          },
          {
            key: 'tracking',
            header: 'Tracking',
            render: (s) =>
              [s.batchTracked && 'Batch', s.serialTracked && 'Serial', s.dateTracked && 'Date']
                .filter(Boolean)
                .join(', ') || '—',
          },
          {
            key: 'status',
            header: 'Status',
            sortable: true,
            sortValue: (s) => s.status ?? '',
            render: (s) => <StatusBadge status={s.status} />,
          },
          {
            key: 'actions',
            header: '',
            render: (s) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setViewing(s)}>
                  View
                </button>
              </div>
            ),
          },
        ]}
      />

      {viewing && <SkuDetailDialog sku={viewing} onClose={() => setViewing(null)} />}
    </div>
  )
}

/** Read-only SKU detail, including its host-owned units of measure and barcodes. */
function SkuDetailDialog({ sku, onClose }: { sku: Sku; onClose: () => void }) {
  const [uoms, setUoms] = useState<UnitOfMeasure[] | null>(null)
  const [barcodes, setBarcodes] = useState<Barcode[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    async function load() {
      try {
        setError(null)
        const [u, b] = await Promise.all([listSkuUoms(sku.id!), listSkuBarcodes(sku.id!)])
        if (active) {
          setUoms(u)
          setBarcodes(b)
        }
      } catch (e) {
        if (active) setError(errMsg(e))
      }
    }
    load()
    return () => {
      active = false
    }
  }, [sku.id])

  const tracking =
    [sku.batchTracked && 'Batch', sku.serialTracked && 'Serial', sku.dateTracked && 'Date']
      .filter(Boolean)
      .join(', ') || 'None'

  return (
    <Dialog title={`SKU ${sku.code}`} onClose={onClose} size="lg">
      <HostManagedNote />
      {error && <div className="alert alert-danger">{error}</div>}
      <div className="md-detail">
        <ReadField label="Code" value={sku.code} />
        <ReadField label="Description" value={sku.description || '—'} />
        <ReadField label="Owner client" value={sku.ownerClient || '—'} />
        <ReadField label="Tracking" value={tracking} />
        <ReadField label="Status" value={<StatusBadge status={sku.status} />} />
      </div>

      <h3 className="md-subhead">Units of measure</h3>
      <table>
        <thead>
          <tr>
            <th>Code</th>
            <th>Base</th>
            <th>Qty in parent</th>
            <th>L×W×H (mm)</th>
            <th>Weight (g)</th>
          </tr>
        </thead>
        <tbody>
          {uoms === null ? (
            <Empty text="Loading…" />
          ) : uoms.length === 0 ? (
            <Empty text="No units of measure." />
          ) : (
            uoms.map((u) => (
              <tr key={u.id}>
                <td>{u.code}</td>
                <td>{u.baseUnit ? 'Yes' : 'No'}</td>
                <td>{u.qtyInParent ?? '—'}</td>
                <td>{[u.lengthMm, u.widthMm, u.heightMm].map((v) => v ?? '·').join(' × ')}</td>
                <td>{u.weightG ?? '—'}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>

      <h3 className="md-subhead">Barcodes</h3>
      <table>
        <thead>
          <tr>
            <th>Value</th>
            <th>UoM</th>
          </tr>
        </thead>
        <tbody>
          {barcodes === null ? (
            <Empty text="Loading…" />
          ) : barcodes.length === 0 ? (
            <Empty text="No barcodes." />
          ) : (
            barcodes.map((b) => {
              const uomCode = uoms?.find((u) => u.id === b.uomId)?.code
              return (
                <tr key={b.id}>
                  <td>
                    <code>{b.value}</code>
                  </td>
                  <td>{uomCode ?? '—'}</td>
                </tr>
              )
            })
          )}
        </tbody>
      </table>

      <div className="dialog-actions">
        <button className="btn btn-ghost btn-sm" onClick={onClose}>
          Close
        </button>
      </div>
    </Dialog>
  )
}

function ReadField({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="md-field">
      <label>{label}</label>
      <div className="md-read-value">{value}</div>
    </div>
  )
}

// =========================================================================
// Storage blocks (warehouse-scoped)
// =========================================================================

const STORAGE_TYPES = ['SHUTTLE_ASRS', 'CRANE_ASRS', 'AUTOSTORE', 'AMR_GTP', 'MANUAL_PICK', 'RESERVE_RACK']
const GRANULARITIES = ['BLOCK', 'LOCATION']

const STORAGE_TYPE_DESCRIPTIONS: Record<string, string> = {
  SHUTTLE_ASRS:
    'Automated shuttle racking (per-level shuttles); the WCS slots to the pool, the system assigns the bin.',
  CRANE_ASRS: 'Crane-served automated aisle (typically one crane per aisle), single/double-deep.',
  AUTOSTORE: 'Grid/bin cube with robots on top.',
  AMR_GTP: 'Autonomous mobile robots bring stock to a goods-to-person station.',
  MANUAL_PICK: 'Operator pick faces; one fixed SKU+UoM per location.',
  RESERVE_RACK: 'Bulk/reserve storage that replenishes pick faces.',
}

// Storage types whose locations are automation slots/bins by default.
const AUTOMATION_STORAGE_TYPES = ['SHUTTLE_ASRS', 'CRANE_ASRS', 'AUTOSTORE', 'AMR_GTP']

/**
 * Safeguarded multi-select for "Allowed HU types" — no free text. Renders the ACTIVE
 * handling-unit type names as toggleable chips; selected names populate the string[].
 * An empty selection means "any".
 */
function AllowedHuTypesPicker({
  value,
  onChange,
}: {
  value: string[]
  onChange: (next: string[]) => void
}) {
  const [types, setTypes] = useState<HandlingUnitType[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    listHandlingUnitTypes()
      .then((all) => {
        if (active) setTypes(all.filter((t) => t.status !== 'ARCHIVED'))
      })
      .catch((e) => {
        if (active) setError(errMsg(e))
      })
    return () => {
      active = false
    }
  }, [])

  function toggle(name: string) {
    onChange(value.includes(name) ? value.filter((n) => n !== name) : [...value, name])
  }

  if (error) return <div className="alert alert-danger">{error}</div>
  if (types === null) return <span className="muted">Loading handling unit types…</span>
  if (types.length === 0) return <span className="muted">No active handling unit types defined.</span>

  return (
    <div className="md-chips">
      {types.map((t) => {
        const on = value.includes(t.name)
        return (
          <button
            key={t.id ?? t.name}
            type="button"
            className={`md-chip${on ? ' is-on' : ''}`}
            aria-pressed={on}
            onClick={() => toggle(t.name)}
          >
            <span className="md-chip-box" aria-hidden="true">
              {on ? '✓' : ''}
            </span>
            {t.name}
          </button>
        )
      })}
    </div>
  )
}

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
  const [guided, setGuided] = useState(false)

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
      <Toolbar label="storage block" onAdd={() => setEditing(blank)}>
        <button className="btn btn-ghost btn-sm" onClick={() => setGuided(true)}>
          Guided builder
        </button>
      </Toolbar>
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={rows}
        rowKey={(b) => b.id ?? b.code}
        search={(b) => `${b.code} ${b.storageType} ${b.slottingGranularity} ${(b.allowedHuTypes ?? []).join(' ')} ${b.status ?? ''}`}
        searchPlaceholder="Search storage blocks…"
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? 'Loading…' : 'No storage blocks for this warehouse.'}
        columns={[
          { key: 'code', header: 'Code', sortable: true, sortValue: (b) => b.code ?? '', render: (b) => b.code },
          {
            key: 'storageType',
            header: 'Storage type',
            sortable: true,
            sortValue: (b) => b.storageType ?? '',
            render: (b) => b.storageType,
          },
          {
            key: 'slottingGranularity',
            header: 'Granularity',
            sortable: true,
            sortValue: (b) => b.slottingGranularity ?? '',
            render: (b) => b.slottingGranularity,
          },
          {
            key: 'gtp',
            header: 'GTP',
            sortable: true,
            sortValue: (b) => (b.gtp ? 1 : 0),
            render: (b) => (b.gtp ? 'Yes' : 'No'),
          },
          {
            key: 'allowedHuTypes',
            header: 'Allowed HU types',
            render: (b) => (b.allowedHuTypes && b.allowedHuTypes.length ? b.allowedHuTypes.join(', ') : 'Any'),
          },
          {
            key: 'status',
            header: 'Status',
            sortable: true,
            sortValue: (b) => b.status ?? '',
            render: (b) => <StatusBadge status={b.status} />,
          },
          {
            key: 'actions',
            header: '',
            render: (b) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(b)}>
                  Edit
                </button>
              </div>
            ),
          },
        ]}
      />
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
      {guided && (
        <GuidedBlockBuilder
          warehouseId={warehouseId}
          onClose={() => setGuided(false)}
          onCompleted={() => {
            setGuided(false)
            load()
          }}
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
  const valid = d.code.trim() !== '' && d.storageType.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? 'Edit storage block' : 'New storage block'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async () => {
        const body = { ...d, allowedHuTypes: d.allowedHuTypes ?? [] }
        if (body.id) await updateStorageBlock(body.id, body)
        else await createStorageBlock(body)
        onSaved()
      }}
    >
      <Field label="Code" required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <Field label="Storage type" required>
        <Select
          value={d.storageType}
          onChange={(val) => setD({ ...d, storageType: val })}
          options={STORAGE_TYPES.map((t) => ({ value: t, label: t }))}
          ariaLabel="Storage type"
        />
      </Field>
      <Field label="Slotting granularity">
        <Select
          value={d.slottingGranularity}
          onChange={(val) => setD({ ...d, slottingGranularity: val })}
          options={GRANULARITIES.map((g) => ({ value: g, label: g }))}
          ariaLabel="Slotting granularity"
        />
      </Field>
      <label className="md-check">
        <input type="checkbox" checked={d.gtp} onChange={(e) => setD({ ...d, gtp: e.target.checked })} />
        Goods-to-person (GTP)
      </label>
      <Field label="Allowed HU types (blank = any)">
        <AllowedHuTypesPicker
          value={d.allowedHuTypes ?? []}
          onChange={(next) => setD({ ...d, allowedHuTypes: next })}
        />
      </Field>
      <Field label="Status">
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// -------------------------------------------------------------------------
// Guided storage-block builder wizard.
//
// Step 1: create the storage block (every field explained + example).
// Step 2: confirm and offer to build its locations.
// Step 3: generate a rack of locations from aisle/levels/positions/sides and
//         bulk-create them; optionally repeat for another aisle.
// -------------------------------------------------------------------------

function GuidedExplain({ children }: { children: React.ReactNode }) {
  return (
    <p className="md-explain" style={{ margin: '.15rem 0 0', fontSize: '.78rem', lineHeight: 1.4 }}>
      {children}
    </p>
  )
}

const SIDES = ['BOTH', 'LEFT', 'RIGHT'] as const
type SideMode = (typeof SIDES)[number]

function GuidedBlockBuilder({
  warehouseId,
  onClose,
  onCompleted,
}: {
  warehouseId: string
  onClose: () => void
  onCompleted: () => void
}) {
  type Step = 'block' | 'confirm' | 'locations'
  const [step, setStep] = useState<Step>('block')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  // ---- Step 1: storage block draft ----
  const [block, setBlock] = useState<StorageBlock>({
    warehouseId,
    code: '',
    storageType: STORAGE_TYPES[0],
    slottingGranularity: 'BLOCK',
    gtp: false,
    allowedHuTypes: [],
    status: 'ACTIVE',
  })
  // The created block (id + code) once step 1 succeeds.
  const [created, setCreated] = useState<StorageBlock | null>(null)
  // Track whether the user has manually overridden the granularity default.
  const [granTouched, setGranTouched] = useState(false)

  function setStorageType(t: string) {
    setBlock((b) => ({
      ...b,
      storageType: t,
      slottingGranularity: granTouched ? b.slottingGranularity : t === 'MANUAL_PICK' ? 'LOCATION' : 'BLOCK',
    }))
  }

  const blockValid = block.code.trim() !== '' && block.storageType.trim() !== ''

  async function createBlock() {
    setBusy(true)
    setError(null)
    try {
      const saved = await createStorageBlock({ ...block, allowedHuTypes: block.allowedHuTypes ?? [] })
      setCreated(saved)
      setStep('confirm')
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  // ---- Step 3: location generator ----
  const isAutomation = AUTOMATION_STORAGE_TYPES.includes(block.storageType)
  const defaultLocType = isAutomation && LOCATION_TYPES.includes('ASRS_SLOT') ? 'ASRS_SLOT' : 'BIN'
  const [aisle, setAisle] = useState('A01')
  const [levels, setLevels] = useState(10)
  const [positions, setPositions] = useState(40)
  const [side, setSide] = useState<SideMode>('BOTH')
  const [locType, setLocType] = useState(defaultLocType)
  const [purpose, setPurpose] = useState('STORAGE')
  const [confirmBig, setConfirmBig] = useState(false)

  const pad = (n: number) => String(n).padStart(2, '0')
  const sideCodes: ('L' | 'R')[] = side === 'BOTH' ? ['L', 'R'] : side === 'LEFT' ? ['L'] : ['R']
  const total = levels * positions * sideCodes.length
  const blockCode = created?.code ?? block.code
  const previewCode = `${blockCode}-${aisle}-L${pad(1)}-P${pad(1)}-${sideCodes[0] ?? 'L'}`
  const genInvalid = !aisle.trim() || levels < 1 || positions < 1
  const tooBig = total > 2000

  function buildLocations(): Location[] {
    if (!created?.id) return []
    const list: Location[] = []
    for (let level = 1; level <= levels; level++) {
      for (let pos = 1; pos <= positions; pos++) {
        for (const sc of sideCodes) {
          list.push({
            warehouseId,
            blockId: created.id,
            code: `${blockCode}-${aisle}-L${pad(level)}-P${pad(pos)}-${sc}`,
            locationType: locType,
            purpose,
            status: 'ACTIVE',
            aisle,
            side: sc === 'L' ? 'LEFT' : 'RIGHT',
            rackLevel: level,
            posX: pos,
            posY: level,
            mixedAllowed: false,
            laneDepth: 1,
          })
        }
      }
    }
    return list
  }

  async function generate() {
    if (tooBig && !confirmBig) {
      setConfirmBig(true)
      return
    }
    setBusy(true)
    setError(null)
    setInfo(null)
    try {
      const list = buildLocations()
      const saved = await bulkCreateLocations(list)
      setInfo(`Created ${saved.length} location${saved.length === 1 ? '' : 's'}.`)
      setConfirmBig(false)
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title="Guided storage-block builder" onClose={onClose} size="lg">
      {error && <div className="alert alert-danger">{error}</div>}

      {step === 'block' && (
        <>
          <p className="muted" style={{ marginTop: 0 }}>
            Step 1 of 3 — describe the storage block.
          </p>
          <div className="md-form">
            <Field label="Code" required>
              <input
                className="form-control"
                value={block.code}
                placeholder="ASRS-A01"
                onChange={(e) => setBlock({ ...block, code: e.target.value })}
              />
              <GuidedExplain>
                Unique code for this block/aisle. e.g. <code>ASRS-A01</code>, <code>PICK-FAST</code>,{' '}
                <code>RESERVE-1</code>.
              </GuidedExplain>
            </Field>

            <Field label="Storage type" required>
              <Select
                value={block.storageType}
                onChange={setStorageType}
                options={STORAGE_TYPES.map((t) => ({ value: t, label: t }))}
                ariaLabel="Storage type"
              />
              <GuidedExplain>{STORAGE_TYPE_DESCRIPTIONS[block.storageType]}</GuidedExplain>
            </Field>

            <Field label="Slotting granularity">
              <Select
                value={block.slottingGranularity}
                onChange={(val) => {
                  setGranTouched(true)
                  setBlock({ ...block, slottingGranularity: val })
                }}
                options={GRANULARITIES.map((g) => ({ value: g, label: g }))}
                ariaLabel="Slotting granularity"
              />
              <GuidedExplain>
                <strong>BLOCK</strong> = automated pool: the WCS slots a SKU to the block and the system holds the exact
                bin. <strong>LOCATION</strong> = fixed pick face: one SKU per location. Defaults to LOCATION for manual
                pick, otherwise BLOCK.
              </GuidedExplain>
            </Field>

            <Field label="Goods-to-person (GTP)">
              <label className="md-check">
                <input
                  type="checkbox"
                  checked={block.gtp}
                  onChange={(e) => setBlock({ ...block, gtp: e.target.checked })}
                />
                Picked at a manned station
              </label>
              <GuidedExplain>Stock is picked at a manned station, not in the aisle.</GuidedExplain>
            </Field>

            <Field label="Allowed HU types (blank = any)">
              <AllowedHuTypesPicker
                value={block.allowedHuTypes ?? []}
                onChange={(next) => setBlock({ ...block, allowedHuTypes: next })}
              />
              <GuidedExplain>
                Which handling units may be stored here, e.g. TOTE for an automated tote aisle. Pallets are
                non-automation. Leave blank to allow any.
              </GuidedExplain>
            </Field>

            <Field label="Status">
              <Select
                value={block.status}
                onChange={(v) => setBlock({ ...block, status: v })}
                options={['ACTIVE', 'INACTIVE'].map((s) => ({ value: s, label: s }))}
                ariaLabel="Status"
              />
            </Field>
          </div>
          <div className="dialog-actions">
            <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={busy}>
              Cancel
            </button>
            <button className="btn btn-primary btn-sm" onClick={createBlock} disabled={busy || !blockValid}>
              {busy ? <span className="spin" /> : 'Create block'}
            </button>
          </div>
        </>
      )}

      {step === 'confirm' && created && (
        <>
          <p style={{ fontSize: '1rem' }}>
            ✓ Block <strong>{created.code}</strong> created. Build its locations now?
          </p>
          <div className="dialog-actions">
            <button className="btn btn-ghost btn-sm" onClick={onCompleted}>
              Done
            </button>
            <button className="btn btn-primary btn-sm" onClick={() => setStep('locations')}>
              Yes, add locations
            </button>
          </div>
        </>
      )}

      {step === 'locations' && created && (
        <>
          <p className="muted" style={{ marginTop: 0 }}>
            Step 3 of 3 — generate locations for block <strong>{created.code}</strong>.
          </p>
          {info && <div className="alert alert-success">{info}</div>}
          <div className="md-form">
            <Field label="Aisle">
              <input
                className="form-control"
                value={aisle}
                placeholder="A01"
                onChange={(e) => {
                  setAisle(e.target.value)
                  setInfo(null)
                  setConfirmBig(false)
                }}
              />
              <GuidedExplain>The aisle identifier, e.g. <code>A01</code>.</GuidedExplain>
            </Field>
            <div className="md-grid-2">
              <Field label="Rack levels">
                <input
                  className="form-control"
                  type="number"
                  min={1}
                  value={levels}
                  onChange={(e) => {
                    setLevels(num(e.target.value) ?? 1)
                    setConfirmBig(false)
                  }}
                />
                <GuidedExplain>
                  Tiers high, e.g. <code>10</code> → L01..L10.
                </GuidedExplain>
              </Field>
              <Field label="Positions per level">
                <input
                  className="form-control"
                  type="number"
                  min={1}
                  value={positions}
                  onChange={(e) => {
                    setPositions(num(e.target.value) ?? 1)
                    setConfirmBig(false)
                  }}
                />
                <GuidedExplain>
                  Bins along the aisle, e.g. <code>40</code> → P01..P40.
                </GuidedExplain>
              </Field>
            </div>
            <Field label="Sides">
              <Select
                value={side}
                onChange={(v) => {
                  setSide(v as SideMode)
                  setConfirmBig(false)
                }}
                options={[
                  { value: 'BOTH', label: 'Both' },
                  { value: 'LEFT', label: 'Left' },
                  { value: 'RIGHT', label: 'Right' },
                ]}
                ariaLabel="Sides"
              />
              <GuidedExplain>Generate one or both sides of the aisle.</GuidedExplain>
            </Field>
            <div className="md-grid-2">
              <Field label="Location type">
                <Select
                  value={locType}
                  onChange={setLocType}
                  options={LOCATION_TYPES.map((t) => ({ value: t, label: t }))}
                  ariaLabel="Location type"
                />
              </Field>
              <Field label="Purpose">
                <Select
                  value={purpose}
                  onChange={setPurpose}
                  options={PURPOSES.map((p) => ({ value: p, label: p }))}
                  ariaLabel="Purpose"
                />
              </Field>
            </div>

            <div className="md-preview">
              <div>
                Code pattern:{' '}
                <code>{`${blockCode}-${aisle || 'A01'}-L{level}-P{pos}-{side}`}</code>
              </div>
              <div>
                Example: <code>{previewCode}</code>
              </div>
              <div>
                Total locations: <strong>{total.toLocaleString()}</strong> ({levels} × {positions} ×{' '}
                {sideCodes.length})
              </div>
            </div>

            {tooBig && (
              <div className="alert alert-warning">
                That is {total.toLocaleString()} locations (over 2,000). Please confirm you really want to generate this
                many.
              </div>
            )}
          </div>
          <div className="dialog-actions">
            <button className="btn btn-ghost btn-sm" onClick={onCompleted} disabled={busy}>
              Done
            </button>
            {info ? (
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => {
                  setAisle('')
                  setInfo(null)
                  setConfirmBig(false)
                }}
              >
                Add another aisle
              </button>
            ) : null}
            <button className="btn btn-primary btn-sm" onClick={generate} disabled={busy || genInvalid}>
              {busy ? (
                <span className="spin" />
              ) : tooBig && confirmBig ? (
                `Generate ${total.toLocaleString()}`
              ) : (
                'Generate'
              )}
            </button>
          </div>
        </>
      )}
    </Dialog>
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
      <DataTable
        rows={rows}
        rowKey={(l) => l.id ?? l.code}
        search={(l) => `${l.code} ${l.locationType} ${l.purpose} ${blockCode(l.blockId)} ${l.aisle ?? ''} ${l.side ?? ''} ${l.status ?? ''}`}
        searchPlaceholder="Search locations…"
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? 'Loading…' : 'No locations for this warehouse.'}
        columns={[
          { key: 'code', header: 'Code', sortable: true, sortValue: (l) => l.code ?? '', render: (l) => l.code },
          {
            key: 'locationType',
            header: 'Type',
            sortable: true,
            sortValue: (l) => l.locationType ?? '',
            render: (l) => l.locationType,
          },
          {
            key: 'purpose',
            header: 'Purpose',
            sortable: true,
            sortValue: (l) => l.purpose ?? '',
            render: (l) => l.purpose,
          },
          {
            key: 'block',
            header: 'Block',
            sortable: true,
            sortValue: (l) => blockCode(l.blockId),
            render: (l) => blockCode(l.blockId),
          },
          {
            key: 'aisle',
            header: 'Aisle',
            sortable: true,
            sortValue: (l) => l.aisle ?? '',
            render: (l) => l.aisle || '—',
          },
          {
            key: 'side',
            header: 'Side',
            sortable: true,
            sortValue: (l) => l.side ?? '',
            render: (l) => l.side || '—',
          },
          {
            key: 'pos',
            header: 'X/Y/Z',
            render: (l) => [l.posX, l.posY, l.posZ].map((v) => v ?? '·').join('/'),
          },
          {
            key: 'distanceToExit',
            header: 'Dist→exit',
            sortable: true,
            sortValue: (l) => l.distanceToExit ?? 0,
            render: (l) => l.distanceToExit ?? '—',
          },
          {
            key: 'status',
            header: 'Status',
            sortable: true,
            sortValue: (l) => l.status ?? '',
            render: (l) => <StatusBadge status={l.status} />,
          },
          {
            key: 'actions',
            header: '',
            render: (l) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(l)}>
                  Edit
                </button>
                <button className="btn btn-danger btn-sm" onClick={() => setDeleting(l)}>
                  Delete
                </button>
              </div>
            ),
          },
        ]}
      />
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
          <Select
            value={d.locationType}
            onChange={(val) => setD({ ...d, locationType: val })}
            options={LOCATION_TYPES.map((t) => ({ value: t, label: t }))}
            ariaLabel="Location type"
          />
        </Field>
        <Field label="Purpose" required>
          <Select
            value={d.purpose}
            onChange={(val) => setD({ ...d, purpose: val })}
            options={PURPOSES.map((p) => ({ value: p, label: p }))}
            ariaLabel="Purpose"
          />
        </Field>
      </div>
      <Field label="Storage block">
        <Select
          value={d.blockId ?? ''}
          onChange={(val) => setD({ ...d, blockId: val || null })}
          options={[
            { value: '', label: '— None —' },
            ...blocks.map((b) => ({ value: b.id ?? '', label: `${b.code} (${b.storageType})` })),
          ]}
          ariaLabel="Storage block"
        />
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
      <DataTable
        rows={rows}
        rowKey={(e) => e.id ?? `${e.family}-${e.vendor}-${e.model}`}
        search={(e) => `${e.family} ${e.vendor ?? ''} ${e.model ?? ''} ${e.adapterEndpoint ?? ''} ${e.status ?? ''}`}
        searchPlaceholder="Search equipment…"
        initialSort={{ key: 'family', dir: 'asc' }}
        empty={loading ? 'Loading…' : 'No equipment for this warehouse.'}
        columns={[
          {
            key: 'family',
            header: 'Family',
            sortable: true,
            sortValue: (e) => e.family ?? '',
            render: (e) => e.family,
          },
          {
            key: 'vendor',
            header: 'Vendor',
            sortable: true,
            sortValue: (e) => e.vendor ?? '',
            render: (e) => e.vendor || '—',
          },
          {
            key: 'model',
            header: 'Model',
            sortable: true,
            sortValue: (e) => e.model ?? '',
            render: (e) => e.model || '—',
          },
          {
            key: 'adapterEndpoint',
            header: 'Adapter endpoint',
            sortable: true,
            sortValue: (e) => e.adapterEndpoint ?? '',
            render: (e) => e.adapterEndpoint || '—',
          },
          {
            key: 'status',
            header: 'Status',
            sortable: true,
            sortValue: (e) => e.status ?? '',
            render: (e) => <StatusBadge status={e.status} />,
          },
          {
            key: 'actions',
            header: '',
            render: (e) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(e)}>
                  Edit
                </button>
              </div>
            ),
          },
        ]}
      />
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
        <Select
          value={d.family}
          onChange={(val) => setD({ ...d, family: val })}
          options={EQUIPMENT_FAMILIES.map((f) => ({ value: f, label: f }))}
          ariaLabel="Family"
        />
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
// Handling unit types (global)
// =========================================================================

function HandlingUnitTypesTab() {
  const { roles } = useAuth()
  const isAdmin = roles.includes('ADMIN')
  const [rows, setRows] = useState<HandlingUnitType[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<HandlingUnitType | null>(null)
  const [showArchived, setShowArchived] = useState(false)
  // Per-row busy flag (archive/restore in flight), keyed by type id.
  const [busyId, setBusyId] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setError(null)
      setRows(await listHandlingUnitTypes())
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [])
  useEffect(() => {
    load()
  }, [load])

  const isArchived = (h: HandlingUnitType) => h.status === 'ARCHIVED'
  // Treat a missing status as ACTIVE. By default only show active types.
  const visible = showArchived ? rows : rows.filter((h) => !isArchived(h))

  async function archive(h: HandlingUnitType) {
    if (!h.id) return
    setError(null)
    setBusyId(h.id)
    try {
      const active = await countActiveHandlingUnits(h.id)
      if (active > 0) {
        setError(`Cannot archive — ${active} active handling unit${active === 1 ? '' : 's'} still use this type.`)
        return
      }
      await archiveHandlingUnitType(h.id)
      await load()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusyId(null)
    }
  }

  async function restore(h: HandlingUnitType) {
    if (!h.id) return
    setError(null)
    setBusyId(h.id)
    try {
      await restoreHandlingUnitType(h.id)
      await load()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusyId(null)
    }
  }

  const blank: HandlingUnitType = {
    name: '',
    nestable: false,
    compartments: 1,
    storableInAutomation: false,
    transportableOnConveyor: false,
    status: 'ACTIVE',
  }

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label="handling unit type" onAdd={() => setEditing(blank)}>
        <label className="md-check">
          <input
            type="checkbox"
            checked={showArchived}
            onChange={(e) => setShowArchived(e.target.checked)}
          />
          Show archived
        </label>
      </Toolbar>
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={visible}
        rowKey={(h) => h.id ?? h.name}
        rowClassName={(h) => (isArchived(h) ? 'md-row-archived' : '')}
        search={(h) => `${h.name} ${h.status ?? ''}`}
        searchPlaceholder="Search handling unit types…"
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={loading ? 'Loading…' : 'No handling unit types yet.'}
        columns={[
          { key: 'name', header: 'Name', sortable: true, sortValue: (h) => h.name ?? '', render: (h) => h.name },
          {
            key: 'dimensions',
            header: 'Dimensions',
            render: (h) => `${h.lengthMm ?? '·'}×${h.widthMm ?? '·'}×${h.heightMm ?? '·'} mm`,
          },
          {
            key: 'weightLimitG',
            header: 'Weight limit',
            sortable: true,
            sortValue: (h) => h.weightLimitG ?? 0,
            render: (h) => (h.weightLimitG != null ? `${h.weightLimitG} g` : '—'),
          },
          {
            key: 'nestable',
            header: 'Nestable',
            sortable: true,
            sortValue: (h) => (h.nestable ? 1 : 0),
            render: (h) => (h.nestable ? 'Yes' : 'No'),
          },
          {
            key: 'compartments',
            header: 'Compartments',
            sortable: true,
            sortValue: (h) => h.compartments ?? 0,
            render: (h) => h.compartments,
          },
          {
            key: 'storableInAutomation',
            header: 'Automation',
            sortable: true,
            sortValue: (h) => (h.storableInAutomation ? 1 : 0),
            render: (h) => (h.storableInAutomation ? 'Yes' : 'No'),
          },
          {
            key: 'transportableOnConveyor',
            header: 'Conveyor',
            sortable: true,
            sortValue: (h) => (h.transportableOnConveyor ? 1 : 0),
            render: (h) => (h.transportableOnConveyor ? 'Yes' : 'No'),
          },
          {
            key: 'status',
            header: 'Status',
            sortable: true,
            sortValue: (h) => h.status ?? 'ACTIVE',
            render: (h) => <StatusBadge status={h.status ?? 'ACTIVE'} />,
          },
          {
            key: 'actions',
            header: '',
            render: (h) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(h)}>
                  Edit
                </button>
                {isAdmin &&
                  (isArchived(h) ? (
                    <button
                      className="btn btn-ghost btn-sm"
                      disabled={busyId === h.id}
                      onClick={() => restore(h)}
                    >
                      {busyId === h.id ? <span className="spin" /> : 'Restore'}
                    </button>
                  ) : (
                    <button
                      className="btn btn-danger btn-sm"
                      disabled={busyId === h.id}
                      onClick={() => archive(h)}
                    >
                      {busyId === h.id ? <span className="spin" /> : 'Archive'}
                    </button>
                  ))}
              </div>
            ),
          },
        ]}
      />

      {editing && (
        <HandlingUnitTypeDialog initial={editing} onClose={() => setEditing(null)} onSaved={load} />
      )}
    </div>
  )
}

function HandlingUnitTypeDialog({
  initial,
  onClose,
  onSaved,
}: {
  initial: HandlingUnitType
  onClose: () => void
  onSaved: () => void
}) {
  const [d, setD] = useState<HandlingUnitType>(initial)
  const valid = d.name.trim() !== '' && d.compartments >= 1 && d.compartments <= 8
  return (
    <EditDialog
      title={initial.id ? 'Edit handling unit type' : 'New handling unit type'}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (h) => {
        if (h.id) await updateHandlingUnitType(h.id, h)
        else await createHandlingUnitType(h)
        onSaved()
      }}
    >
      <Field label="Name" required>
        <input className="form-control" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} />
      </Field>
      <div className="md-grid-3">
        <Field label="Length (mm)">
          <input
            className="form-control"
            type="number"
            value={d.lengthMm ?? ''}
            onChange={(e) => setD({ ...d, lengthMm: num(e.target.value) ?? undefined })}
          />
        </Field>
        <Field label="Width (mm)">
          <input
            className="form-control"
            type="number"
            value={d.widthMm ?? ''}
            onChange={(e) => setD({ ...d, widthMm: num(e.target.value) ?? undefined })}
          />
        </Field>
        <Field label="Height (mm)">
          <input
            className="form-control"
            type="number"
            value={d.heightMm ?? ''}
            onChange={(e) => setD({ ...d, heightMm: num(e.target.value) ?? undefined })}
          />
        </Field>
      </div>
      <div className="md-grid-2">
        <Field label="Weight limit (g)">
          <input
            className="form-control"
            type="number"
            value={d.weightLimitG ?? ''}
            onChange={(e) => setD({ ...d, weightLimitG: num(e.target.value) ?? undefined })}
          />
        </Field>
        <Field label="Compartments (1–8)">
          <input
            className="form-control"
            type="number"
            min={1}
            max={8}
            value={d.compartments}
            onChange={(e) => setD({ ...d, compartments: num(e.target.value) ?? 1 })}
          />
        </Field>
      </div>
      <div className="md-checks">
        <label className="md-check">
          <input type="checkbox" checked={d.nestable} onChange={(e) => setD({ ...d, nestable: e.target.checked })} />
          Nestable
        </label>
        <label className="md-check">
          <input
            type="checkbox"
            checked={d.storableInAutomation}
            onChange={(e) => setD({ ...d, storableInAutomation: e.target.checked })}
          />
          Storable in automation
        </label>
        <label className="md-check">
          <input
            type="checkbox"
            checked={d.transportableOnConveyor}
            onChange={(e) => setD({ ...d, transportableOnConveyor: e.target.checked })}
          />
          Transportable on conveyor
        </label>
      </div>
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
      <DataTable
        rows={rows}
        rowKey={(t) => t.id ?? t.code}
        search={(t) => `${t.code} ${t.name ?? ''} ${t.status ?? ''}`}
        searchPlaceholder="Search label templates…"
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? 'Loading…' : 'No label templates yet.'}
        columns={[
          { key: 'code', header: 'Code', sortable: true, sortValue: (t) => t.code ?? '', render: (t) => t.code },
          {
            key: 'name',
            header: 'Name',
            sortable: true,
            sortValue: (t) => t.name ?? '',
            render: (t) => t.name || '—',
          },
          {
            key: 'size',
            header: 'Size (mm)',
            render: (t) => `${t.widthMm} × ${t.heightMm}`,
          },
          {
            key: 'dpi',
            header: 'DPI',
            sortable: true,
            sortValue: (t) => t.dpi ?? 0,
            render: (t) => t.dpi,
          },
          {
            key: 'elements',
            header: 'Elements',
            sortable: true,
            sortValue: (t) => t.elements?.length ?? 0,
            render: (t) => t.elements?.length ?? 0,
          },
          {
            key: 'status',
            header: 'Status',
            sortable: true,
            sortValue: (t) => t.status ?? '',
            render: (t) => <StatusBadge status={t.status} />,
          },
          {
            key: 'actions',
            header: '',
            render: (t) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(t)}>
                  Edit
                </button>
                <button className="btn btn-danger btn-sm" onClick={() => setDeleting(t)}>
                  Delete
                </button>
              </div>
            ),
          },
        ]}
      />

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
      .md-row-archived td { opacity: .55; }
      .md-scroll-x { overflow-x: auto; }
      .md-form { display: flex; flex-direction: column; gap: .85rem; }
      .md-form-2col { display: grid; grid-template-columns: 1fr 1fr; gap: .85rem 1rem; }
      .md-field { display: flex; flex-direction: column; }
      .md-grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: .85rem; }
      .md-grid-3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: .85rem; }
      .md-checks { display: flex; flex-wrap: wrap; gap: 1rem; }
      .md-check { display: inline-flex; align-items: center; gap: .45rem; color: var(--text); font-size: .875rem; margin: 0; }
      .md-check input { width: auto; }
      .md-host-note { display: flex; align-items: center; gap: .6rem; margin-bottom: 1rem; }
      .md-detail { display: grid; grid-template-columns: 1fr 1fr; gap: .85rem; margin-bottom: 1rem; }
      .md-read-value { padding: .4rem 0; color: var(--text); font-size: .9rem; }
      .md-subhead { font-size: .9rem; margin: 1.1rem 0 .5rem; }
      .md-chips { display: flex; flex-wrap: wrap; gap: .5rem; }
      .md-chip { display: inline-flex; align-items: center; gap: .4rem; padding: .35rem .7rem; border-radius: 999px;
        border: 1px solid var(--border, rgba(255,255,255,.18)); background: transparent; color: var(--text);
        font-size: .82rem; cursor: pointer; }
      .md-chip:hover { border-color: var(--accent, #c6ff00); }
      .md-chip.is-on { background: rgba(198,255,0,.12); border-color: var(--accent, #c6ff00); color: var(--text); }
      .md-chip-box { display: inline-flex; align-items: center; justify-content: center; width: 1rem; height: 1rem;
        font-size: .7rem; color: var(--accent, #c6ff00); }
      .md-explain { color: var(--muted, rgba(255,255,255,.6)); }
      .md-explain code { font-size: .78rem; }
      .md-preview { border: 1px dashed var(--border, rgba(255,255,255,.18)); border-radius: .5rem; padding: .7rem .85rem;
        display: flex; flex-direction: column; gap: .3rem; font-size: .85rem; }
      .alert-success { background: rgba(108, 219, 90, .12); color: #9be37e; border: 1px solid rgba(108, 219, 90, .3); }
      .alert-warning { background: rgba(255, 196, 0, .12); color: #ffd566; border: 1px solid rgba(255, 196, 0, .3); }
    `}</style>
  )
}
