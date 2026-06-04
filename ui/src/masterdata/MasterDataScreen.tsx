import { useCallback, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useParams } from 'react-router-dom'
import { useWarehouse } from '../warehouse/WarehouseContext'
import Select from '../ui/Select'
import DataTable from '../ui/DataTable'
import {
  Barcode,
  Equipment,
  LabelTemplate,
  Location,
  Sku,
  StorageBlock,
  UnitOfMeasure,
  Warehouse,
  createEquipment,
  createLabelTemplate,
  createLocation,
  createStorageBlock,
  createWarehouse,
  deleteLabelTemplate,
  deleteLocation,
  deleteWarehouse,
  listEquipment,
  listLabelTemplates,
  listLocations,
  listSkuBarcodes,
  listSkuUoms,
  listSkus,
  listStorageBlocks,
  listWarehouses,
  updateEquipment,
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

// Full IANA timezone list (falls back to a short list on engines without Intl.supportedValuesOf).
const TZ_OPTIONS = (() => {
  const supported = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf
  const zones = supported
    ? supported('timeZone')
    : ['UTC', 'Europe/Vienna', 'Europe/London', 'Europe/Berlin', 'America/New_York', 'America/Los_Angeles', 'Asia/Singapore', 'Australia/Sydney']
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
      .md-scroll-x { overflow-x: auto; }
      .md-form { display: flex; flex-direction: column; gap: .85rem; }
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
    `}</style>
  )
}
