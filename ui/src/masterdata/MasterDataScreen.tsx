import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useLocation } from 'react-router-dom'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { useAuth } from '../auth/AuthContext'
import Select from '../ui/Select'
import DataTable from '../ui/DataTable'
import InfoTip from '../ui/InfoTip'
import { useT } from '../i18n/useT'
import { checkOccupancy, countActiveHandlingUnits } from '../inventory/api'
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
  archiveStorageBlock,
  bulkCreateLocations,
  createEquipment,
  createHandlingUnitType,
  createLabelTemplate,
  createLocation,
  createStorageBlock,
  createWarehouse,
  deleteLabelTemplate,
  deleteLocation,
  deleteStorageBlock,
  deleteWarehouse,
  listBlockLocationIds,
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
  restoreStorageBlock,
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

const ENTITIES: { key: EntityKey; labelKey: string; label: string; scoped: boolean }[] = [
  { key: 'warehouses', labelKey: 'entWarehouses', label: 'Warehouses', scoped: false },
  { key: 'skus', labelKey: 'entSkus', label: 'SKUs', scoped: false },
  { key: 'storage-blocks', labelKey: 'entStorageBlocks', label: 'Storage blocks', scoped: true },
  { key: 'locations', labelKey: 'entLocations', label: 'Locations', scoped: true },
  { key: 'equipment', labelKey: 'entEquipment', label: 'Equipment', scoped: true },
  { key: 'handling-unit-types', labelKey: 'entHuTypes', label: 'Handling unit types', scoped: false },
  { key: 'label-templates', labelKey: 'entLabelTemplates', label: 'Label templates', scoped: false },
]

// Whether the signed-in user may write on the current master-data catalog. The shared write
// affordances (Toolbar "+ New", EditDialog Save, ConfirmDelete, inline Delete/Archive) read this
// so a READ-level user gets a view-only screen. The gateway independently enforces the writes.
const MdWriteContext = createContext(true)
const useMdWrite = () => useContext(MdWriteContext)

export default function MasterDataScreen() {
  const t = useT('masterdata')
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const { writeAllowed } = useAuth()
  // The active entity is the last segment of /master-data/<entity> — each catalog is its own route.
  const { pathname } = useLocation()
  const entity: EntityKey = ENTITIES.find((e) => e.key === pathname.split('/')[2])?.key ?? 'warehouses'
  const canWrite = writeAllowed(`master-data:${entity}`)
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
    <MdWriteContext.Provider value={canWrite}>
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">{t('eyebrow', 'Master data')}</span>
        <h1>{t(active.labelKey, active.label)}</h1>
        <p>
          {active.scoped
            ? t('scopedNote', 'Scoped to the warehouse selected in the top bar.')
            : entity === 'skus'
              ? t('skusNote', 'SKUs, with their units of measure and barcodes, are owned by the host system and shown read-only.')
              : t('manageCatalog', 'Manage this master-data catalog.')}
        </p>
      </div>

      {!canWrite && entity !== 'skus' && (
        <div className="alert" role="status" style={{ marginBottom: '1rem' }}>
          <span className="badge badge-info">{t('viewOnly', 'View only')}</span>{' '}
          {t('viewOnlyNote', 'You have read access to this catalog. Editing is disabled.')}
        </div>
      )}

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
    </MdWriteContext.Provider>
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
  label: React.ReactNode
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
  const t = useT('masterdata')
  const canWrite = useMdWrite()
  return (
    <div className="toolbar">
      {children}
      <div className="spacer" />
      {canWrite && (
        <button className="btn btn-primary btn-sm" onClick={onAdd}>
          {t('newPrefix', '+ New')} {label}
        </button>
      )}
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
  const t = useT('masterdata')
  const canWrite = useMdWrite()
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
          {canWrite ? t('cancel', 'Cancel') : t('close', 'Close')}
        </button>
        {canWrite && (
          <button className="btn btn-primary btn-sm" onClick={save} disabled={saving || !canSave}>
            {saving ? <span className="spin" /> : t('save', 'Save')}
          </button>
        )}
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
  const t = useT('masterdata')
  const canWrite = useMdWrite()
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
    <Dialog title={t('confirmDelete', 'Confirm delete')} onClose={onClose}>
      {error && <div className="alert alert-danger">{error}</div>}
      <p>
        {t('archiveQ', 'Archive')} <strong>{name}</strong>? {t('willBeMarked', 'It will be marked')} <code>ARCHIVED</code>.
      </p>
      <div className="dialog-actions">
        <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={busy}>
          {canWrite ? t('cancel', 'Cancel') : t('close', 'Close')}
        </button>
        {canWrite && (
          <button className="btn btn-danger btn-sm" onClick={go} disabled={busy}>
            {busy ? <span className="spin" /> : t('archive', 'Archive')}
          </button>
        )}
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
  const t = useT('masterdata')
  return (
    <Select
      value={value}
      onChange={onChange}
      options={STATUS_OPTIONS.map((s) => ({ value: s, label: s }))}
      ariaLabel={t('status', 'Status')}
    />
  )
}

// =========================================================================
// Warehouses
// =========================================================================

function WarehousesTab({ onWarehousesChanged }: { onWarehousesChanged: () => void }) {
  const t = useT('masterdata')
  const canWrite = useMdWrite()
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
      <Toolbar label={t('lblWarehouse', 'warehouse')} onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={rows}
        rowKey={(w) => w.id ?? w.code}
        search={(w) => `${w.code} ${w.name} ${w.timezone} ${w.status}`}
        searchPlaceholder={t('searchWarehouses', 'Search warehouses…')}
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? t('loading', 'Loading…') : t('noWarehouses', 'No warehouses yet.')}
        columns={[
          { key: 'code', header: t('colCode', 'Code'), sortable: true, sortValue: (w) => w.code ?? '', render: (w) => w.code },
          { key: 'name', header: t('colName', 'Name'), sortable: true, sortValue: (w) => w.name ?? '', render: (w) => w.name },
          { key: 'timezone', header: t('colTimezone', 'Timezone'), sortable: true, sortValue: (w) => w.timezone ?? '', render: (w) => w.timezone },
          { key: 'status', header: t('colStatus', 'Status'), sortable: true, sortValue: (w) => w.status ?? '', render: (w) => <StatusBadge status={w.status} /> },
          {
            key: 'actions',
            header: '',
            render: (w) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(w)}>
                  {canWrite ? t('edit', 'Edit') : t('view', 'View')}
                </button>
                {canWrite && (
                  <button className="btn btn-danger btn-sm" onClick={() => setDeleting(w)}>
                    {t('delete', 'Delete')}
                  </button>
                )}
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
  const t = useT('masterdata')
  const [d, setD] = useState<Warehouse>(initial)
  const valid = d.code.trim() !== '' && d.name.trim() !== '' && d.timezone.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? t('editWarehouse', 'Edit warehouse') : t('newWarehouse', 'New warehouse')}
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
      <Field label={<>{t('fldCode', 'Code')} <InfoTip text={t('tipWhCode', 'Short unique identifier for this warehouse; fixed once saved.')} example="DC-01" /></>} required>
        <input
          className="form-control"
          value={d.code}
          readOnly={!!initial.id}
          title={initial.id ? t('codeFixed', 'Code is fixed once set') : undefined}
          onChange={(e) => setD({ ...d, code: e.target.value })}
        />
      </Field>
      <Field label={<>{t('fldName', 'Name')} <InfoTip text={t('tipWhName', 'Human-readable name of the warehouse site.')} example="Vienna Distribution Centre" /></>} required>
        <input className="form-control" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} />
      </Field>
      <Field label={<>{t('fldTimezone', 'Timezone')} <InfoTip text={t('tipWhTimezone', 'Local timezone of the site, used for timestamps and operational reporting.')} example="UTC+1" /></>} required>
        <Select ariaLabel={t('fldTimezone', 'Timezone')} value={d.timezone} onChange={(v) => setD({ ...d, timezone: v })} options={TZ_OPTIONS} />
      </Field>
      <Field label={<>{t('fldAddress1', 'Address line 1')} <InfoTip text={t('tipWhAddr1', 'Street and number of the warehouse address.')} example="Industriestrasse 12" /></>}>
        <input className="form-control" value={d.addressLine1 ?? ''} onChange={(e) => setD({ ...d, addressLine1: e.target.value })} />
      </Field>
      <Field label={<>{t('fldAddress2', 'Address line 2')} <InfoTip text={t('tipWhAddr2', 'Optional second address line (building, unit, dock).')} example="Building C, Gate 4" /></>}>
        <input className="form-control" value={d.addressLine2 ?? ''} onChange={(e) => setD({ ...d, addressLine2: e.target.value })} />
      </Field>
      <Field label={<>{t('fldCity', 'City')} <InfoTip text={t('tipWhCity', 'City the warehouse is located in.')} example="Vienna" /></>}>
        <input className="form-control" value={d.city ?? ''} onChange={(e) => setD({ ...d, city: e.target.value })} />
      </Field>
      <Field label={<>{t('fldRegion', 'Region / State')} <InfoTip text={t('tipWhRegion', 'State, province or region of the site.')} example="Lower Austria" /></>}>
        <input className="form-control" value={d.region ?? ''} onChange={(e) => setD({ ...d, region: e.target.value })} />
      </Field>
      <Field label={<>{t('fldPostalCode', 'Postal code')} <InfoTip text={t('tipWhPostal', 'Postal / ZIP code of the address.')} example="1010" /></>}>
        <input className="form-control" value={d.postalCode ?? ''} onChange={(e) => setD({ ...d, postalCode: e.target.value })} />
      </Field>
      <Field label={<>{t('fldCountry', 'Country')} <InfoTip text={t('tipWhCountry', 'Country the warehouse is in.')} example="Austria" /></>}>
        <input className="form-control" value={d.country ?? ''} onChange={(e) => setD({ ...d, country: e.target.value })} />
      </Field>
      <Field label={<>{t('fldStatus', 'Status')} <InfoTip text={t('tipStatusFull', 'Lifecycle state: ACTIVE is in use, INACTIVE is paused, ARCHIVED is retired.')} example="ACTIVE" /></>}>
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
  const t = useT('masterdata')
  return (
    <div className="alert md-host-note">
      <span className="badge badge-warning">{t('hostManaged', 'Host-managed')}</span>
      {t('hostManagedNote', 'Managed by the host system, read-only in the WCS. SKUs, units of measure and barcodes are kept in sync from the host and cannot be created, edited or deleted here.')}
    </div>
  )
}

function SkusTab() {
  const t = useT('masterdata')
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
          placeholder={t('searchSkuDescBarcode', 'Search code / description / barcode…')}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && load(search)}
        />
        <button className="btn btn-ghost btn-sm" onClick={() => load(search)}>
          {t('searchBtn', 'Search')}
        </button>
        <div className="spacer" />
      </div>
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={rows}
        rowKey={(s) => s.id ?? s.code}
        search={(s) => `${s.code} ${s.description ?? ''} ${s.ownerClient ?? ''} ${s.status ?? ''}`}
        searchPlaceholder={t('filterLoadedSkus', 'Filter loaded SKUs…')}
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? t('loading', 'Loading…') : t('noSkus', 'No SKUs found.')}
        columns={[
          { key: 'code', header: t('colCode', 'Code'), sortable: true, sortValue: (s) => s.code ?? '', render: (s) => s.code },
          {
            key: 'description',
            header: t('colDescription', 'Description'),
            sortable: true,
            sortValue: (s) => s.description ?? '',
            render: (s) => s.description || '—',
          },
          {
            key: 'ownerClient',
            header: t('colOwner', 'Owner'),
            sortable: true,
            sortValue: (s) => s.ownerClient ?? '',
            render: (s) => s.ownerClient || '—',
          },
          {
            key: 'tracking',
            header: t('colTracking', 'Tracking'),
            render: (s) =>
              [s.batchTracked && t('trkBatch', 'Batch'), s.serialTracked && t('trkSerial', 'Serial'), s.dateTracked && t('trkDate', 'Date')]
                .filter(Boolean)
                .join(', ') || '—',
          },
          {
            key: 'status',
            header: t('colStatus', 'Status'),
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
                  {t('view', 'View')}
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
  const t = useT('masterdata')
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
    [sku.batchTracked && t('trkBatch', 'Batch'), sku.serialTracked && t('trkSerial', 'Serial'), sku.dateTracked && t('trkDate', 'Date')]
      .filter(Boolean)
      .join(', ') || t('trkNone', 'None')

  return (
    <Dialog title={`SKU ${sku.code}`} onClose={onClose} size="lg">
      <HostManagedNote />
      {error && <div className="alert alert-danger">{error}</div>}
      <div className="md-detail">
        <ReadField label={t('colCode', 'Code')} value={sku.code} />
        <ReadField label={t('colDescription', 'Description')} value={sku.description || '—'} />
        <ReadField label={t('ownerClient', 'Owner client')} value={sku.ownerClient || '—'} />
        <ReadField label={t('colTracking', 'Tracking')} value={tracking} />
        <ReadField label={t('colStatus', 'Status')} value={<StatusBadge status={sku.status} />} />
      </div>

      <h3 className="md-subhead">{t('unitsOfMeasure', 'Units of measure')}</h3>
      <table>
        <thead>
          <tr>
            <th>{t('colCode', 'Code')}</th>
            <th>{t('colBase', 'Base')}</th>
            <th>{t('colQtyInParent', 'Qty in parent')}</th>
            <th>{t('colLwhMm', 'L×W×H (mm)')}</th>
            <th>{t('colWeightG', 'Weight (g)')}</th>
          </tr>
        </thead>
        <tbody>
          {uoms === null ? (
            <Empty text={t('loading', 'Loading…')} />
          ) : uoms.length === 0 ? (
            <Empty text={t('noUoms', 'No units of measure.')} />
          ) : (
            uoms.map((u) => (
              <tr key={u.id}>
                <td>{u.code}</td>
                <td>{u.baseUnit ? t('yes', 'Yes') : t('no', 'No')}</td>
                <td>{u.qtyInParent ?? '—'}</td>
                <td>{[u.lengthMm, u.widthMm, u.heightMm].map((v) => v ?? '·').join(' × ')}</td>
                <td>{u.weightG ?? '—'}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>

      <h3 className="md-subhead">{t('barcodes', 'Barcodes')}</h3>
      <table>
        <thead>
          <tr>
            <th>{t('colValue', 'Value')}</th>
            <th>{t('colUom', 'UoM')}</th>
          </tr>
        </thead>
        <tbody>
          {barcodes === null ? (
            <Empty text={t('loading', 'Loading…')} />
          ) : barcodes.length === 0 ? (
            <Empty text={t('noBarcodes', 'No barcodes.')} />
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
          {t('close', 'Close')}
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

// Automated storage types are always goods-to-person; manual pick is a genuine
// choice; everything else (reserve) is not goods-to-person.
const GTP_AUTOMATED = ['SHUTTLE_ASRS', 'CRANE_ASRS', 'AUTOSTORE', 'AMR_GTP']
const isGtpEditable = (type: string) => type === 'MANUAL_PICK'
function derivedGtp(type: string, current = false): boolean {
  if (GTP_AUTOMATED.includes(type)) return true
  if (type === 'MANUAL_PICK') return current
  return false
}

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
  const tr = useT('masterdata')
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
  if (types === null) return <span className="muted">{tr('loadingHuTypes', 'Loading handling unit types…')}</span>
  if (types.length === 0) return <span className="muted">{tr('noActiveHuTypes', 'No active handling unit types defined.')}</span>

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
  const t = useT('masterdata')
  if (warehouseId) return null
  return <div className="alert">{t('selectWhToManage', 'Select a warehouse above to manage this entity.')}</div>
}

function StorageBlocksTab({
  warehouseId,
  warehouseName,
}: {
  warehouseId: string
  warehouseName: (id?: string | null) => string
}) {
  const t = useT('masterdata')
  const { roles } = useAuth()
  const canWrite = useMdWrite()
  const isAdmin = roles.includes('ADMIN')
  const [rows, setRows] = useState<StorageBlock[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<StorageBlock | null>(null)
  const [guided, setGuided] = useState(false)
  const [showArchived, setShowArchived] = useState(false)
  // Per-row busy flag (archive/restore/delete in flight), keyed by block id.
  const [busyId, setBusyId] = useState<string | null>(null)

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

  const isArchived = (b: StorageBlock) => b.status === 'ARCHIVED'
  // Treat a missing status as ACTIVE. By default only show non-archived blocks.
  const visible = showArchived ? rows : rows.filter((b) => !isArchived(b))

  async function archive(b: StorageBlock) {
    if (!b.id) return
    setError(null)
    setBusyId(b.id)
    try {
      await archiveStorageBlock(b.id)
      await load()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusyId(null)
    }
  }

  async function restore(b: StorageBlock) {
    if (!b.id) return
    setError(null)
    setBusyId(b.id)
    try {
      await restoreStorageBlock(b.id)
      await load()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusyId(null)
    }
  }

  async function remove(b: StorageBlock) {
    if (!b.id) return
    setError(null)
    setBusyId(b.id)
    try {
      // Gate: a block may only be deleted when its locations hold no stock / handling units.
      const ids = await listBlockLocationIds(warehouseId, b.id)
      if (ids.length > 0) {
        const { stockRows, handlingUnits } = await checkOccupancy(ids)
        if (stockRows > 0 || handlingUnits > 0) {
          setError(
            `${t('cannotDelete', 'Cannot delete:')} ${stockRows} ${t('stockRowsWord', 'stock rows')} ${t('andWord', 'and')} ${handlingUnits} ${t('handlingUnitsWord', 'handling units')} ${t('occupyLocations', "occupy this block's locations.")}`,
          )
          return
        }
      }
      if (!window.confirm(`${t('deleteBlockPre', 'Delete block')} ${b.code} ${t('andItsWord', 'and its')} ${ids.length} ${t('locationsWord', 'locations')}?`)) {
        return
      }
      await deleteStorageBlock(b.id)
      await load()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusyId(null)
    }
  }

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
      <Toolbar label={t('lblStorageBlock', 'storage block')} onAdd={() => setEditing(blank)}>
        <button className="btn btn-ghost btn-sm" onClick={() => setGuided(true)}>
          {t('guidedBuilder', 'Guided builder')}
        </button>
        <label className="md-check">
          <input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />
          {t('showArchived', 'Show archived')}
        </label>
      </Toolbar>
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={visible}
        rowKey={(b) => b.id ?? b.code}
        rowClassName={(b) => (isArchived(b) ? 'md-row-archived' : '')}
        search={(b) => `${b.code} ${b.storageType} ${b.slottingGranularity} ${(b.allowedHuTypes ?? []).join(' ')} ${b.status ?? ''}`}
        searchPlaceholder={t('searchStorageBlocks', 'Search storage blocks…')}
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? t('loading', 'Loading…') : t('noStorageBlocksWh', 'No storage blocks for this warehouse.')}
        columns={[
          { key: 'code', header: t('colCode', 'Code'), sortable: true, sortValue: (b) => b.code ?? '', render: (b) => b.code },
          {
            key: 'storageType',
            header: t('colStorageType', 'Storage type'),
            sortable: true,
            sortValue: (b) => b.storageType ?? '',
            render: (b) => b.storageType,
          },
          {
            key: 'slottingGranularity',
            header: t('colGranularity', 'Granularity'),
            sortable: true,
            sortValue: (b) => b.slottingGranularity ?? '',
            render: (b) => b.slottingGranularity,
          },
          {
            key: 'gtp',
            header: t('colGtp', 'GTP'),
            sortable: true,
            sortValue: (b) => (b.gtp ? 1 : 0),
            render: (b) => (b.gtp ? t('yes', 'Yes') : t('no', 'No')),
          },
          {
            key: 'allowedHuTypes',
            header: t('colAllowedHuTypes', 'Allowed HU types'),
            render: (b) => (b.allowedHuTypes && b.allowedHuTypes.length ? b.allowedHuTypes.join(', ') : t('any', 'Any')),
          },
          {
            key: 'status',
            header: t('colStatus', 'Status'),
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
                  {canWrite ? t('edit', 'Edit') : t('view', 'View')}
                </button>
                {isAdmin && canWrite && (
                  <>
                    {isArchived(b) ? (
                      <button className="btn btn-ghost btn-sm" disabled={busyId === b.id} onClick={() => restore(b)}>
                        {busyId === b.id ? <span className="spin" /> : t('restore', 'Restore')}
                      </button>
                    ) : (
                      <button className="btn btn-ghost btn-sm" disabled={busyId === b.id} onClick={() => archive(b)}>
                        {busyId === b.id ? <span className="spin" /> : t('archive', 'Archive')}
                      </button>
                    )}
                    <button className="btn btn-danger btn-sm" disabled={busyId === b.id} onClick={() => remove(b)}>
                      {busyId === b.id ? <span className="spin" /> : t('delete', 'Delete')}
                    </button>
                  </>
                )}
              </div>
            ),
          },
        ]}
      />
      <p className="muted" style={{ fontSize: '.8rem', marginTop: '.75rem' }}>
        {t('warehouseLabel', 'Warehouse:')} {warehouseName(warehouseId)}. {t('blockDeleteNote', 'A block can only be deleted when its locations hold no stock or handling units.')}
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
  const t = useT('masterdata')
  const [d, setD] = useState<StorageBlock>(initial)
  // Equipment options for the optional FK link (the device that serves this block).
  const [equipment, setEquipment] = useState<Equipment[]>([])
  useEffect(() => {
    let active = true
    if (!initial.warehouseId) return
    listEquipment(initial.warehouseId)
      .then((list) => {
        if (active) setEquipment(list)
      })
      .catch(() => {
        /* non-fatal: the select just stays at "— none —" */
      })
    return () => {
      active = false
    }
  }, [initial.warehouseId])
  const equipmentOptions = [
    { value: '', label: t('optNone', '— none —') },
    ...equipment.map((e) => ({
      value: e.id ?? '',
      label: `${e.subtype ? `${e.type}/${e.subtype}` : e.type ?? e.family} · ${[e.vendor, e.model]
        .filter(Boolean)
        .join(' ') || '—'}`,
    })),
  ]
  const valid = d.code.trim() !== '' && d.storageType.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? t('editStorageBlock', 'Edit storage block') : t('newStorageBlock', 'New storage block')}
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
      <Field label={<>{t('fldCode', 'Code')} <InfoTip text={t('tipBlockCode', 'Unique code for this storage block within the warehouse.')} example="ASRS-A01" /></>} required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <Field label={<>{t('fldStorageType', 'Storage type')} <InfoTip text={t('tipStorageType', 'The kind of storage/automation this block represents; drives slotting and GTP behaviour.')} example="SHUTTLE_ASRS" /></>} required>
        <Select
          value={d.storageType}
          onChange={(val) => setD({ ...d, storageType: val, gtp: derivedGtp(val, d.gtp) })}
          options={STORAGE_TYPES.map((st) => ({ value: st, label: st }))}
          ariaLabel={t('fldStorageType', 'Storage type')}
        />
      </Field>
      <Field label={<>{t('fldSlottingGranularity', 'Slotting granularity')} <InfoTip text={t('tipSlottingGranularity', 'BLOCK = WCS slots to the pool and the system holds the exact bin; LOCATION = one fixed SKU per location.')} example="BLOCK" /></>}>
        <Select
          value={d.slottingGranularity}
          onChange={(val) => setD({ ...d, slottingGranularity: val })}
          options={GRANULARITIES.map((g) => ({ value: g, label: g }))}
          ariaLabel={t('fldSlottingGranularity', 'Slotting granularity')}
        />
      </Field>
      {isGtpEditable(d.storageType) ? (
        <label className="md-check">
          <input type="checkbox" checked={d.gtp} onChange={(e) => setD({ ...d, gtp: e.target.checked })} />
          {t('gtpMannedStation', 'Goods-to-person (GTP), picked at a manned station')}{' '}
          <InfoTip text={t('tipGtpManned', 'When on, stock from this block is picked at a manned goods-to-person station rather than in the aisle.')} example="on for a manual GTP pick block" />
        </label>
      ) : GTP_AUTOMATED.includes(d.storageType) ? (
        <p className="md-explain" style={{ margin: '.15rem 0 0', fontSize: '.78rem', lineHeight: 1.4 }}>
          {t('gtpImpliedBy', 'Goods-to-person, implied by')} {d.storageType}.
        </p>
      ) : null}
      <Field label={<>{t('fldAllowedHuTypes', 'Allowed HU types (blank = any)')} <InfoTip text={t('tipAllowedHuTypes', 'Restrict which handling-unit types may be stored in this block. Leave none selected to allow any.')} example="TOTE for an automated tote aisle" /></>}>
        <AllowedHuTypesPicker
          value={d.allowedHuTypes ?? []}
          onChange={(next) => setD({ ...d, allowedHuTypes: next })}
        />
      </Field>
      <Field label={<>{t('fldEquipment', 'Equipment')} <InfoTip text={t('tipBlockEquipment', 'The equipment that serves this storage area, e.g. the ASRS aisle.')} example="ASRS Shuttle A" /></>}>
        <Select
          value={d.equipmentId ?? ''}
          onChange={(val) => setD({ ...d, equipmentId: val === '' ? null : val })}
          options={equipmentOptions}
          ariaLabel={t('fldEquipment', 'Equipment')}
        />
      </Field>
      <Field label={<>{t('fldStatus', 'Status')} <InfoTip text={t('tipStatusFull', 'Lifecycle state: ACTIVE is in use, INACTIVE is paused, ARCHIVED is retired.')} example="ACTIVE" /></>}>
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
  const t = useT('masterdata')
  const storageTypeDesc = (type: string): string =>
    t(`storageTypeDesc_${type}`, STORAGE_TYPE_DESCRIPTIONS[type] ?? '')
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
      gtp: derivedGtp(t, b.gtp),
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
  const [laneDepth, setLaneDepth] = useState(1)
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
            // Travel rank to the aisle's in/outfeed (assumed at position 1, ground level);
            // drives the velocity-to-exit objective in slotting. Editable per location.
            distanceToExit: pos - 1 + (level - 1),
            mixedAllowed: false,
            laneDepth,
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
      setInfo(`${t('createdWord', 'Created')} ${saved.length} ${t('locationsWord', 'locations')}.`)
      setConfirmBig(false)
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog title={t('guidedBuilderTitle', 'Guided storage-block builder')} onClose={onClose} size="lg">
      {error && <div className="alert alert-danger">{error}</div>}

      {step === 'block' && (
        <>
          <p className="muted" style={{ marginTop: 0 }}>
            {t('step1of3', 'Step 1 of 3: describe the storage block.')}
          </p>
          <div className="md-form">
            <Field label={<>{t('fldCode', 'Code')} <InfoTip text={t('tipGuidedBlockCode', 'Unique code for this storage block / aisle within the warehouse.')} example="ASRS-A01" /></>} required>
              <input
                className="form-control"
                value={block.code}
                placeholder="ASRS-A01"
                onChange={(e) => setBlock({ ...block, code: e.target.value })}
              />
              <GuidedExplain>
                {t('explainBlockCode', 'Unique code for this block/aisle. e.g.')} <code>ASRS-A01</code>, <code>PICK-FAST</code>,{' '}
                <code>RESERVE-1</code>.
              </GuidedExplain>
            </Field>

            <Field label={<>{t('fldStorageType', 'Storage type')} <InfoTip text={t('tipGuidedStorageType', 'The kind of storage/automation this block represents; drives slotting and goods-to-person behaviour.')} example="SHUTTLE_ASRS" /></>} required>
              <Select
                value={block.storageType}
                onChange={setStorageType}
                options={STORAGE_TYPES.map((st) => ({ value: st, label: st }))}
                ariaLabel={t('fldStorageType', 'Storage type')}
              />
              <GuidedExplain>{storageTypeDesc(block.storageType)}</GuidedExplain>
            </Field>

            <Field label={<>{t('fldSlottingGranularity', 'Slotting granularity')} <InfoTip text={t('tipGuidedGranularity', 'BLOCK = automated pool, system holds the exact bin; LOCATION = fixed pick face, one SKU per location.')} example="BLOCK" /></>}>
              <Select
                value={block.slottingGranularity}
                onChange={(val) => {
                  setGranTouched(true)
                  setBlock({ ...block, slottingGranularity: val })
                }}
                options={GRANULARITIES.map((g) => ({ value: g, label: g }))}
                ariaLabel={t('fldSlottingGranularity', 'Slotting granularity')}
              />
              <GuidedExplain>
                {t('explainGranularity', 'BLOCK = automated pool: the WCS slots a SKU to the block and the system holds the exact bin. LOCATION = fixed pick face: one SKU per location. Defaults to LOCATION for manual pick, otherwise BLOCK.')}
              </GuidedExplain>
            </Field>

            {isGtpEditable(block.storageType) ? (
              <Field label={<>{t('gtpLabel', 'Goods-to-person (GTP)')} <InfoTip text={t('tipGuidedGtp', 'When on, stock from this block is picked at a manned station rather than walked to in the aisle.')} example="on for a manual GTP pick block" /></>}>
                <label className="md-check">
                  <input
                    type="checkbox"
                    checked={block.gtp}
                    onChange={(e) => setBlock({ ...block, gtp: e.target.checked })}
                  />
                  {t('pickedAtMannedStation', 'Picked at a manned station')}
                </label>
                <GuidedExplain>{t('explainGtpManned', 'Stock is picked at a manned station, not in the aisle.')}</GuidedExplain>
              </Field>
            ) : GTP_AUTOMATED.includes(block.storageType) ? (
              <Field label={t('gtpLabel', 'Goods-to-person (GTP)')}>
                <GuidedExplain>{t('gtpImpliedBy', 'Goods-to-person, implied by')} {block.storageType}.</GuidedExplain>
              </Field>
            ) : null}

            <Field label={<>{t('fldAllowedHuTypes', 'Allowed HU types (blank = any)')} <InfoTip text={t('tipGuidedAllowedHu', 'Restrict which handling-unit types may be stored in this block. Select none to allow any.')} example="TOTE for an automated tote aisle" /></>}>
              <AllowedHuTypesPicker
                value={block.allowedHuTypes ?? []}
                onChange={(next) => setBlock({ ...block, allowedHuTypes: next })}
              />
              <GuidedExplain>
                {t('explainAllowedHu', 'Which handling units may be stored here, e.g. TOTE for an automated tote aisle. Pallets are non-automation. Leave blank to allow any.')}
              </GuidedExplain>
            </Field>

            <Field label={<>{t('fldStatus', 'Status')} <InfoTip text={t('tipGuidedStatus', 'Lifecycle state of the new block: ACTIVE is in use, INACTIVE is paused.')} example="ACTIVE" /></>}>
              <Select
                value={block.status}
                onChange={(v) => setBlock({ ...block, status: v })}
                options={['ACTIVE', 'INACTIVE'].map((s) => ({ value: s, label: s }))}
                ariaLabel={t('fldStatus', 'Status')}
              />
            </Field>
          </div>
          <div className="dialog-actions">
            <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={busy}>
              {t('cancel', 'Cancel')}
            </button>
            <button className="btn btn-primary btn-sm" onClick={createBlock} disabled={busy || !blockValid}>
              {busy ? <span className="spin" /> : t('createBlock', 'Create block')}
            </button>
          </div>
        </>
      )}

      {step === 'confirm' && created && (
        <>
          <p style={{ fontSize: '1rem' }}>
            ✓ {t('blockWord', 'Block')} <strong>{created.code}</strong> {t('createdBuildNow', 'created. Build its locations now?')}
          </p>
          <div className="dialog-actions">
            <button className="btn btn-ghost btn-sm" onClick={onCompleted}>
              {t('done', 'Done')}
            </button>
            <button className="btn btn-primary btn-sm" onClick={() => setStep('locations')}>
              {t('yesAddLocations', 'Yes, add locations')}
            </button>
          </div>
        </>
      )}

      {step === 'locations' && created && (
        <>
          <p className="muted" style={{ marginTop: 0 }}>
            {t('step3of3', 'Step 3 of 3: generate locations for block')} <strong>{created.code}</strong>.
          </p>
          {info && <div className="alert alert-success">{info}</div>}
          <div className="md-form">
            <Field label={<>{t('fldAisle', 'Aisle')} <InfoTip text={t('tipAisle', 'Aisle identifier; becomes part of every generated location code.')} example="A01" /></>}>
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
              <GuidedExplain>{t('explainAisle', 'The aisle identifier, e.g.')} <code>A01</code>.</GuidedExplain>
            </Field>
            <div className="md-grid-2">
              <Field label={<>{t('fldRackLevels', 'Rack levels')} <InfoTip text={t('tipRackLevels', 'Number of vertical tiers in the rack; generates L01..Lnn.')} example="10" /></>}>
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
                  {t('explainRackLevels', 'Tiers high, e.g.')} <code>10</code> → L01..L10.
                </GuidedExplain>
              </Field>
              <Field label={<>{t('fldPositionsPerLevel', 'Positions per level')} <InfoTip text={t('tipPositions', 'Number of bin positions along the aisle on each level; generates P01..Pnn.')} example="40" /></>}>
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
                  {t('explainPositions', 'Bins along the aisle, e.g.')} <code>40</code> → P01..P40.
                </GuidedExplain>
              </Field>
            </div>
            <Field label={<>{t('fldLaneDepth', 'Lane depth')} <InfoTip text={t('tipLaneDepthGuided', 'How many handling units deep each lane is: 1 = single-deep, 2 = double-deep, more for shuttle/satellite lanes.')} example="2" /></>}>
              <input
                className="form-control"
                type="number"
                min={1}
                value={laneDepth}
                onChange={(e) => {
                  setLaneDepth(num(e.target.value) ?? 1)
                  setConfirmBig(false)
                }}
              />
              <GuidedExplain>
                {t('explainLaneDepth', 'How many handling units deep each lane is: 1 = single-deep, 2 = double-deep, more for shuttle/satellite lanes. e.g.')} <code>2</code>.
              </GuidedExplain>
            </Field>
            <Field label={<>{t('fldSides', 'Sides')} <InfoTip text={t('tipSides', "Generate locations for one or both sides of the aisle; sets each location's LEFT/RIGHT side.")} example="Both" /></>}>
              <Select
                value={side}
                onChange={(v) => {
                  setSide(v as SideMode)
                  setConfirmBig(false)
                }}
                options={[
                  { value: 'BOTH', label: t('sideBoth', 'Both') },
                  { value: 'LEFT', label: t('sideLeft', 'Left') },
                  { value: 'RIGHT', label: t('sideRight', 'Right') },
                ]}
                ariaLabel={t('fldSides', 'Sides')}
              />
              <GuidedExplain>{t('explainSides', 'Generate one or both sides of the aisle.')}</GuidedExplain>
            </Field>
            <div className="md-grid-2">
              <Field label={<>{t('fldLocationType', 'Location type')} <InfoTip text={t('tipLocTypeGuided', 'Physical kind of each generated location; ASRS slots/bins for automation, shelves/pallets for manual.')} example="ASRS_SLOT" /></>}>
                <Select
                  value={locType}
                  onChange={setLocType}
                  options={LOCATION_TYPES.map((lt) => ({ value: lt, label: lt }))}
                  ariaLabel={t('fldLocationType', 'Location type')}
                />
              </Field>
              <Field label={<>{t('fldPurpose', 'Purpose')} <InfoTip text={t('tipPurposeGuided', 'What the generated locations are used for in the flow (storage, picking, staging, etc.).')} example="STORAGE" /></>}>
                <Select
                  value={purpose}
                  onChange={setPurpose}
                  options={PURPOSES.map((p) => ({ value: p, label: p }))}
                  ariaLabel={t('fldPurpose', 'Purpose')}
                />
              </Field>
            </div>

            <div className="md-preview">
              <div>
                {t('codePattern', 'Code pattern:')}{' '}
                <code>{`${blockCode}-${aisle || 'A01'}-L{level}-P{pos}-{side}`}</code>
              </div>
              <div>
                {t('exampleLabel', 'Example:')} <code>{previewCode}</code>
              </div>
              <div>
                {t('totalLocations', 'Total locations:')} <strong>{total.toLocaleString()}</strong> ({levels} × {positions} ×{' '}
                {sideCodes.length})
              </div>
            </div>

            {tooBig && (
              <div className="alert alert-warning">
                {t('tooBigPre', 'That is')} {total.toLocaleString()} {t('tooBigPost', 'locations (over 2,000). Please confirm you really want to generate this many.')}
              </div>
            )}
          </div>
          <div className="dialog-actions">
            <button className="btn btn-ghost btn-sm" onClick={onCompleted} disabled={busy}>
              {t('done', 'Done')}
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
                {t('addAnotherAisle', 'Add another aisle')}
              </button>
            ) : null}
            <button className="btn btn-primary btn-sm" onClick={generate} disabled={busy || genInvalid}>
              {busy ? (
                <span className="spin" />
              ) : tooBig && confirmBig ? (
                `${t('generateWord', 'Generate')} ${total.toLocaleString()}`
              ) : (
                t('generate', 'Generate')
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
  const t = useT('masterdata')
  const canWrite = useMdWrite()
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
      <Toolbar label={t('lblLocation', 'location')} onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={rows}
        rowKey={(l) => l.id ?? l.code}
        search={(l) => `${l.code} ${l.locationType} ${l.purpose} ${blockCode(l.blockId)} ${l.aisle ?? ''} ${l.side ?? ''} ${l.status ?? ''}`}
        searchPlaceholder={t('searchLocations', 'Search locations…')}
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? t('loading', 'Loading…') : t('noLocations', 'No locations for this warehouse.')}
        columns={[
          { key: 'code', header: t('colCode', 'Code'), sortable: true, sortValue: (l) => l.code ?? '', render: (l) => l.code },
          {
            key: 'locationType',
            header: t('colType', 'Type'),
            sortable: true,
            sortValue: (l) => l.locationType ?? '',
            render: (l) => l.locationType,
          },
          {
            key: 'purpose',
            header: t('colPurpose', 'Purpose'),
            sortable: true,
            sortValue: (l) => l.purpose ?? '',
            render: (l) => l.purpose,
          },
          {
            key: 'block',
            header: t('colBlock', 'Block'),
            sortable: true,
            sortValue: (l) => blockCode(l.blockId),
            render: (l) => blockCode(l.blockId),
          },
          {
            key: 'aisle',
            header: t('colAisle', 'Aisle'),
            sortable: true,
            sortValue: (l) => l.aisle ?? '',
            render: (l) => l.aisle || '—',
          },
          {
            key: 'side',
            header: t('colSide', 'Side'),
            sortable: true,
            sortValue: (l) => l.side ?? '',
            render: (l) => l.side || '—',
          },
          {
            key: 'pos',
            header: t('colXyz', 'X/Y/Z'),
            render: (l) => [l.posX, l.posY, l.posZ].map((v) => v ?? '·').join('/'),
          },
          {
            key: 'distanceToExit',
            header: t('colDistExit', 'Dist→exit'),
            sortable: true,
            sortValue: (l) => l.distanceToExit ?? 0,
            render: (l) => l.distanceToExit ?? '—',
          },
          {
            key: 'status',
            header: t('colStatus', 'Status'),
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
                  {canWrite ? t('edit', 'Edit') : t('view', 'View')}
                </button>
                {canWrite && (
                  <button className="btn btn-danger btn-sm" onClick={() => setDeleting(l)}>
                    {t('delete', 'Delete')}
                  </button>
                )}
              </div>
            ),
          },
        ]}
      />
      <p className="muted" style={{ fontSize: '.8rem', marginTop: '.75rem' }}>
        {t('warehouseLabel', 'Warehouse:')} {warehouseName(warehouseId)}.
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
  const t = useT('masterdata')
  const [d, setD] = useState<Location>(initial)
  const valid = d.code.trim() !== '' && d.locationType.trim() !== '' && d.purpose.trim() !== ''
  return (
    <EditDialog
      title={initial.id ? t('editLocation', 'Edit location') : t('newLocation', 'New location')}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (l) => {
        if (l.id) await updateLocation(l.id, l)
        else await createLocation(l)
        onSaved()
      }}
    >
      <Field label={<>{t('fldCode', 'Code')} <InfoTip text={t('tipLocCode', 'Unique code identifying this individual location within the warehouse.')} example="ASRS-A01-L03-P12-L" /></>} required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <div className="md-grid-2">
        <Field label={<>{t('fldLocationType', 'Location type')} <InfoTip text={t('tipLocType', 'Physical kind of location; drives how it can be used and which devices serve it.')} example="ASRS_SLOT" /></>} required>
          <Select
            value={d.locationType}
            onChange={(val) => setD({ ...d, locationType: val })}
            options={LOCATION_TYPES.map((lt) => ({ value: lt, label: lt }))}
            ariaLabel={t('fldLocationType', 'Location type')}
          />
        </Field>
        <Field label={<>{t('fldPurpose', 'Purpose')} <InfoTip text={t('tipPurpose', 'What this location is used for in the flow (storage, picking, staging, etc.).')} example="STORAGE" /></>} required>
          <Select
            value={d.purpose}
            onChange={(val) => setD({ ...d, purpose: val })}
            options={PURPOSES.map((p) => ({ value: p, label: p }))}
            ariaLabel={t('fldPurpose', 'Purpose')}
          />
        </Field>
      </div>
      <Field label={<>{t('fldStorageBlock', 'Storage block')} <InfoTip text={t('tipLocStorageBlock', 'The storage block this location belongs to; leave as None for a standalone location.')} example="ASRS-A01 (SHUTTLE_ASRS)" /></>}>
        <Select
          value={d.blockId ?? ''}
          onChange={(val) => setD({ ...d, blockId: val || null })}
          options={[
            { value: '', label: t('optNoneCap', '— None —') },
            ...blocks.map((b) => ({ value: b.id ?? '', label: `${b.code} (${b.storageType})` })),
          ]}
          ariaLabel={t('fldStorageBlock', 'Storage block')}
        />
      </Field>
      <div className="md-grid-2">
        <Field label={<>{t('fldAisle', 'Aisle')} <InfoTip text={t('tipLocAisle', 'Aisle this location sits in; used for routing and grouping.')} example="A01" /></>}>
          <input
            className="form-control"
            value={d.aisle ?? ''}
            onChange={(e) => setD({ ...d, aisle: e.target.value || null })}
          />
        </Field>
        <Field label={<>{t('fldSide', 'Side')} <InfoTip text={t('tipLocSide', 'Which side of the aisle the location is on.')} example="LEFT" /></>}>
          <input
            className="form-control"
            value={d.side ?? ''}
            placeholder="LEFT / RIGHT"
            onChange={(e) => setD({ ...d, side: e.target.value || null })}
          />
        </Field>
      </div>
      <div className="md-grid-3">
        <Field label={<>{t('fldPosX', 'Pos X')} <InfoTip text={t('tipPosX', 'Horizontal position along the aisle (column/bay index).')} example="12" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.posX ?? ''}
            onChange={(e) => setD({ ...d, posX: num(e.target.value) })}
          />
        </Field>
        <Field label={<>{t('fldPosY', 'Pos Y')} <InfoTip text={t('tipPosY', 'Vertical position / rack level index.')} example="3" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.posY ?? ''}
            onChange={(e) => setD({ ...d, posY: num(e.target.value) })}
          />
        </Field>
        <Field label={<>{t('fldPosZ', 'Pos Z')} <InfoTip text={t('tipPosZ', 'Depth position into the lane (for multi-deep storage).')} example="1" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.posZ ?? ''}
            onChange={(e) => setD({ ...d, posZ: num(e.target.value) })}
          />
        </Field>
      </div>
      <div className="md-grid-2">
        <Field label={<>{t('fldDistanceToExit', 'Distance to exit')} <InfoTip text={t('tipDistanceToExit', 'Relative travel distance from this location to the aisle exit; used to prioritise faster locations during slotting.')} example="45" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.distanceToExit ?? ''}
            onChange={(e) => setD({ ...d, distanceToExit: num(e.target.value) })}
          />
        </Field>
        <Field label={<>{t('fldHardwareAddress', 'Hardware address')} <InfoTip text={t('tipHardwareAddress', 'Controller / device address for this location (e.g. an ASRS bin address). Optional, can be filled in later.')} example="A01.R02.L05.D1" /></>}>
          <input
            className="form-control"
            value={d.hardwareAddress ?? ''}
            onChange={(e) => setD({ ...d, hardwareAddress: e.target.value || null })}
          />
        </Field>
        <Field label={<>{t('fldLaneDepth', 'Lane depth')} <InfoTip text={t('tipLocLaneDepth', "How many handling units deep this location's lane is: 1 = single-deep, 2 = double-deep.")} example="2" /></>}>
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
        {t('mixedSkusAllowed', 'Mixed SKUs allowed')}{' '}
        <InfoTip text={t('tipMixedSkus', 'When on, this location may hold more than one SKU at a time; off restricts it to a single SKU.')} example="off for a fixed pick face" />
      </label>
      <Field label={<>{t('fldStatus', 'Status')} <InfoTip text={t('tipStatusLocation', 'Lifecycle state: ACTIVE is in use, INACTIVE is blocked, ARCHIVED is retired.')} example="ACTIVE" /></>}>
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Equipment (warehouse-scoped)
// =========================================================================

// Routing families the derivation can produce; family still drives routing/adapters.
const EQUIPMENT_FAMILIES = ['CONVEYOR', 'ASRS', 'AMR', 'AUTOSTORE'] as const
void EQUIPMENT_FAMILIES

// ---------------------------------------------------------------------------
// Automation-topology taxonomy. The user picks a Type (and Subtype for ASRS /
// SORTER); the routing `family` (CONVEYOR | ASRS | AMR | AUTOSTORE) is derived
// from that and kept set silently, because it still drives routing/adapters.
// ---------------------------------------------------------------------------

const EQUIPMENT_TYPES: { value: string; label: string }[] = [
  { value: 'BIN_CONVEYOR', label: 'Bin conveyor' },
  { value: 'PALLET_CONVEYOR', label: 'Pallet conveyor' },
  { value: 'LONGREACH_CONVEYOR', label: 'Longreach conveyor (load/unload)' },
  { value: 'ASRS', label: 'ASRS' },
  { value: 'SORTER', label: 'Sorter' },
]

const CONVEYOR_TYPES = ['BIN_CONVEYOR', 'PALLET_CONVEYOR', 'LONGREACH_CONVEYOR']

const EQUIPMENT_SUBTYPES: Record<string, { value: string; label: string }[]> = {
  ASRS: [
    { value: 'SHUTTLE', label: 'SHUTTLE' },
    { value: 'CRANE', label: 'CRANE' },
    { value: 'AMR', label: 'AMR' },
    { value: 'CUBE', label: 'CUBE (AutoStore)' },
  ],
  SORTER: [
    { value: 'CROSSBELT', label: 'CROSSBELT' },
    { value: 'TILTTRAY', label: 'TILTTRAY' },
    { value: 'SHOE', label: 'SHOE' },
  ],
}

// Process types that can sit on a conveyor / sorter segment.
const PROCESS_TYPES: { value: string; label: string }[] = [
  { value: 'SCAN', label: 'SCAN' },
  { value: 'LABEL_APPLICATOR', label: 'LABEL_APPLICATOR' },
  { value: 'DIVERT_LEFT', label: 'DIVERT_LEFT' },
  { value: 'DIVERT_RIGHT', label: 'DIVERT_RIGHT' },
  { value: 'DWS', label: 'DWS (Dimension/Weight Scan)' },
  { value: 'QUERY_POINT', label: 'QUERY_POINT (hold HU till release)' },
  { value: 'WRAPPER', label: 'WRAPPER' },
]

// Types that may carry inline process types (the 3 conveyors + SORTER).
const PROCESS_CAPABLE_TYPES = [...CONVEYOR_TYPES, 'SORTER']

/**
 * Derive the routing family from the chosen Type/Subtype. Family still drives
 * routing/adapters, so it must always stay set.
 * BIN/PALLET/LONGREACH conveyor + SORTER → CONVEYOR; ASRS+SHUTTLE/CRANE → ASRS;
 * ASRS+AMR → AMR; ASRS+CUBE → AUTOSTORE.
 */
function deriveFamily(type: string, subtype?: string | null): string {
  if (CONVEYOR_TYPES.includes(type) || type === 'SORTER') return 'CONVEYOR'
  if (type === 'ASRS') {
    switch (subtype) {
      case 'AMR':
        return 'AMR'
      case 'CUBE':
        return 'AUTOSTORE'
      default:
        return 'ASRS' // SHUTTLE, CRANE (and unset) route as ASRS
    }
  }
  return 'CONVEYOR'
}

const equipmentTypeLabel = (type?: string | null) =>
  EQUIPMENT_TYPES.find((t) => t.value === type)?.label ?? null

/**
 * Safeguarded multi-select for equipment process types — no free text. Renders
 * the known process types as toggleable chips; selected values populate the
 * string[]. Matches the storage-block "Allowed HU types" picker styling.
 */
function ProcessTypesPicker({
  value,
  onChange,
}: {
  value: string[]
  onChange: (next: string[]) => void
}) {
  function toggle(name: string) {
    onChange(value.includes(name) ? value.filter((n) => n !== name) : [...value, name])
  }
  return (
    <div className="md-chips">
      {PROCESS_TYPES.map((p) => {
        const on = value.includes(p.value)
        return (
          <button
            key={p.value}
            type="button"
            className={`md-chip${on ? ' is-on' : ''}`}
            aria-pressed={on}
            onClick={() => toggle(p.value)}
          >
            <span className="md-chip-box" aria-hidden="true">
              {on ? '✓' : ''}
            </span>
            {p.label}
          </button>
        )
      })}
    </div>
  )
}

// Equipment families/types that serve storage areas (blocks) — only then do we
// surface the "Storage areas" multi-select linking blocks via block.equipmentId.
const STORAGE_SERVING_FAMILIES = ['ASRS', 'AUTOSTORE', 'AMR']
function servesStorage(d: Equipment): boolean {
  return d.type === 'ASRS' || STORAGE_SERVING_FAMILIES.includes(d.family)
}

/**
 * Multi-select of the warehouse's storage blocks for an ASRS-style device. Chips
 * styled like the HU-types / process-types pickers; selected block ids populate
 * the set, which the dialog reconciles into each block's equipmentId on save.
 */
function StorageAreasPicker({
  blocks,
  loading,
  error,
  selected,
  onToggle,
}: {
  blocks: StorageBlock[]
  loading: boolean
  error: string | null
  selected: Set<string>
  onToggle: (id: string) => void
}) {
  const t = useT('masterdata')
  if (error) return <div className="alert alert-danger">{error}</div>
  if (loading) return <span className="muted">{t('loadingStorageBlocks', 'Loading storage blocks…')}</span>
  if (blocks.length === 0) return <span className="muted">{t('noStorageBlocksWhShort', 'No storage blocks in this warehouse.')}</span>
  return (
    <div className="md-chips">
      {blocks.map((b) => {
        const on = !!b.id && selected.has(b.id)
        return (
          <button
            key={b.id ?? b.code}
            type="button"
            className={`md-chip${on ? ' is-on' : ''}`}
            aria-pressed={on}
            onClick={() => b.id && onToggle(b.id)}
          >
            <span className="md-chip-box" aria-hidden="true">
              {on ? '✓' : ''}
            </span>
            {b.code} · {b.storageType}
          </button>
        )
      })}
    </div>
  )
}

function EquipmentTab({
  warehouseId,
  warehouseName,
}: {
  warehouseId: string
  warehouseName: (id?: string | null) => string
}) {
  const t = useT('masterdata')
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
    type: EQUIPMENT_TYPES[0].value,
    subtype: null,
    family: deriveFamily(EQUIPMENT_TYPES[0].value, null),
    defaultWidthM: null,
    defaultHeightM: null,
    defaultLengthM: null,
    processTypes: [],
    vendor: '',
    model: '',
    adapterEndpoint: '',
    status: 'ACTIVE',
  }

  if (!warehouseId) return <ScopeNotice warehouseId={warehouseId} />

  return (
    <div className="glass card-pad md-panel">
      <Toolbar label={t('lblEquipment', 'equipment')} onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={rows}
        rowKey={(e) => e.id ?? `${e.family}-${e.vendor}-${e.model}`}
        search={(e) =>
          `${equipmentTypeLabel(e.type) ?? ''} ${e.type ?? ''} ${e.subtype ?? ''} ${e.family} ${e.vendor ?? ''} ${
            e.model ?? ''
          } ${e.adapterEndpoint ?? ''} ${(e.processTypes ?? []).join(' ')} ${e.status ?? ''}`
        }
        searchPlaceholder={t('searchEquipment', 'Search equipment…')}
        initialSort={{ key: 'type', dir: 'asc' }}
        empty={loading ? t('loading', 'Loading…') : t('noEquipment', 'No equipment for this warehouse.')}
        columns={[
          {
            key: 'type',
            header: t('colType', 'Type'),
            sortable: true,
            // Legacy rows without a type fall back to the routing family.
            sortValue: (e) => equipmentTypeLabel(e.type) ?? e.family ?? '',
            render: (e) => {
              const label = equipmentTypeLabel(e.type) ?? e.family ?? '—'
              return e.subtype ? `${label} · ${e.subtype}` : label
            },
          },
          {
            key: 'vendor',
            header: t('colVendor', 'Vendor'),
            sortable: true,
            sortValue: (e) => e.vendor ?? '',
            render: (e) => e.vendor || '—',
          },
          {
            key: 'model',
            header: t('colModel', 'Model'),
            sortable: true,
            sortValue: (e) => e.model ?? '',
            render: (e) => e.model || '—',
          },
          {
            key: 'adapterEndpoint',
            header: t('colAdapterEndpoint', 'Adapter endpoint'),
            sortable: true,
            sortValue: (e) => e.adapterEndpoint ?? '',
            render: (e) => e.adapterEndpoint || '—',
          },
          {
            key: 'status',
            header: t('colStatus', 'Status'),
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
                  {t('edit', 'Edit')}
                </button>
              </div>
            ),
          },
        ]}
      />
      <p className="muted" style={{ fontSize: '.8rem', marginTop: '.75rem' }}>
        {t('warehouseLabel', 'Warehouse:')} {warehouseName(warehouseId)}. {t('equipmentArchiveNote', 'Equipment has no archive endpoint; set status to ARCHIVED via Edit.')}
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
  const t = useT('masterdata')
  // Translated option labels for the Type select and the descriptive process types.
  const equipmentTypeOptions = EQUIPMENT_TYPES.map((o) => ({ value: o.value, label: t(`eqType_${o.value}`, o.label) }))
  // Derive a sensible Type for legacy rows that only have a routing family.
  const [d, setD] = useState<Equipment>(() => {
    const type = initial.type ?? (initial.family === 'CONVEYOR' ? 'BIN_CONVEYOR' : 'ASRS')
    return { ...initial, type, family: deriveFamily(type, initial.subtype ?? null) }
  })
  const valid = !!d.type && d.type.trim() !== ''
  const subtypeOptions = d.type ? EQUIPMENT_SUBTYPES[d.type] : undefined
  const showProcessTypes = !!d.type && PROCESS_CAPABLE_TYPES.includes(d.type)
  const isConveyor = !!d.type && CONVEYOR_TYPES.includes(d.type)
  const showStorageAreas = servesStorage(d)

  // Warehouse storage blocks + the set of those linked to THIS equipment (by id).
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [blocksLoading, setBlocksLoading] = useState(false)
  const [blocksError, setBlocksError] = useState<string | null>(null)
  const [linked, setLinked] = useState<Set<string>>(new Set())
  useEffect(() => {
    let active = true
    if (!initial.warehouseId) return
    setBlocksLoading(true)
    listStorageBlocks(initial.warehouseId)
      .then((list) => {
        if (!active) return
        setBlocks(list)
        // Pre-check blocks already pointing at this equipment (edit mode only).
        setLinked(new Set(list.filter((b) => b.id && b.equipmentId === initial.id).map((b) => b.id!)))
      })
      .catch((e) => {
        if (active) setBlocksError(errMsg(e))
      })
      .finally(() => {
        if (active) setBlocksLoading(false)
      })
    return () => {
      active = false
    }
  }, [initial.warehouseId, initial.id])

  function toggleBlock(id: string) {
    setLinked((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  // When Type changes: reset/initialise subtype, clear process types if the new
  // type can't carry them, and re-derive the routing family.
  function changeType(type: string) {
    const opts = EQUIPMENT_SUBTYPES[type]
    const subtype = opts ? (opts.some((o) => o.value === d.subtype) ? d.subtype : opts[0].value) : null
    const processTypes = PROCESS_CAPABLE_TYPES.includes(type) ? d.processTypes ?? [] : []
    setD({ ...d, type, subtype, processTypes, family: deriveFamily(type, subtype) })
  }

  function changeSubtype(subtype: string) {
    setD({ ...d, subtype, family: deriveFamily(d.type ?? '', subtype) })
  }

  return (
    <EditDialog
      title={initial.id ? t('editEquipment', 'Edit equipment') : t('newEquipment', 'New equipment')}
      draft={d}
      canSave={valid}
      wide
      onClose={onClose}
      onSave={async (e) => {
        // Save the equipment first so a freshly-created device has an id to link to.
        const saved = e.id ? await updateEquipment(e.id, e) : await createEquipment(e)
        const savedId = saved.id
        // Reconcile the block ⇄ equipment links via each block's equipmentId.
        // Only ASRS-style devices expose the picker; for others `linked` is whatever
        // was loaded (the blocks already pointing here), so nothing changes.
        if (savedId && servesStorage(saved)) {
          await Promise.all(
            blocks.map((b) => {
              if (!b.id) return null
              const wantLinked = linked.has(b.id)
              if (wantLinked && b.equipmentId !== savedId) {
                return updateStorageBlock(b.id, { ...b, equipmentId: savedId })
              }
              if (!wantLinked && b.equipmentId === savedId) {
                return updateStorageBlock(b.id, { ...b, equipmentId: null })
              }
              return null
            }),
          )
        }
        onSaved()
      }}
    >
      <Field
        label={
          <>
            {t('fldType', 'Type')}{' '}
            <InfoTip
              text={t('tipEqType', 'What kind of automation this device is. Drives the editor below and the routing family. e.g. a bin conveyor segment, an ASRS aisle or a sorter.')}
              example="Bin conveyor"
            />
          </>
        }
        required
      >
        <Select
          value={d.type ?? ''}
          onChange={changeType}
          options={equipmentTypeOptions}
          ariaLabel={t('fldType', 'Type')}
        />
      </Field>

      {subtypeOptions && (
        <Field
          label={
            <>
              {t('fldSubtype', 'Subtype')}{' '}
              <InfoTip
                text={t('tipSubtype', 'The specific technology within the type. ASRS: SHUTTLE, CRANE, AMR or CUBE (AutoStore). SORTER: CROSSBELT, TILTTRAY or SHOE.')}
                example="SHUTTLE"
              />
            </>
          }
          required
        >
          <Select
            value={d.subtype ?? ''}
            onChange={changeSubtype}
            options={subtypeOptions}
            ariaLabel={t('fldSubtype', 'Subtype')}
          />
        </Field>
      )}

      <Field
        label={
          <>
            {t('fldRoutingFamily', 'Routing family')}{' '}
            <InfoTip
              text={t('tipRoutingFamily', 'Derived automatically from Type/Subtype; it drives routing and which device adapter is used. ASRS+CUBE produces AUTOSTORE, ASRS+AMR produces AMR, conveyors/sorters produce CONVEYOR.')}
              example="CONVEYOR"
            />
          </>
        }
      >
        <div className="md-read-value">{d.family}</div>
      </Field>

      <Field
        label={
          <>
            {t('fldDefaultWidth', 'Default size, width (m)')}{' '}
            <InfoTip
              text={t('tipDefaultWidth', 'Default footprint width in metres, used when this equipment is first placed in the automation topology.')}
              example="0.6"
            />
          </>
        }
      >
        <input
          className="form-control"
          type="number"
          step={0.1}
          value={d.defaultWidthM ?? ''}
          onChange={(e) => setD({ ...d, defaultWidthM: num(e.target.value) })}
        />
      </Field>
      <Field
        label={
          <>
            {t('fldDefaultHeight', 'Default size, height (m)')}{' '}
            <InfoTip
              text={t('tipDefaultHeight', 'Default height in metres (deck / pick height), used when placed in the automation topology.')}
              example="0.9"
            />
          </>
        }
      >
        <input
          className="form-control"
          type="number"
          step={0.1}
          value={d.defaultHeightM ?? ''}
          onChange={(e) => setD({ ...d, defaultHeightM: num(e.target.value) })}
        />
      </Field>
      <Field
        label={
          <>
            {t('fldDefaultLength', 'Default size, length (m)')}{' '}
            <InfoTip
              text={t('tipDefaultLength', 'Default length in metres. For conveyors the real length is set later when the segment is placed/sized in the automation topology.')}
              example="2.0"
            />
          </>
        }
      >
        <input
          className="form-control"
          type="number"
          step={0.1}
          value={d.defaultLengthM ?? ''}
          onChange={(e) => setD({ ...d, defaultLengthM: num(e.target.value) })}
        />
        {isConveyor && (
          <p className="md-explain" style={{ margin: '.15rem 0 0', fontSize: '.78rem', lineHeight: 1.4 }}>
            {t('conveyorLengthNote', 'For conveyors the length is set later when the segment is placed in the automation topology.')}
          </p>
        )}
      </Field>

      {showProcessTypes && (
        <Field
          label={
            <>
              {t('fldProcessTypes', 'Process types')}{' '}
              <InfoTip
                text={t('tipProcessTypes', 'In-line processing this conveyor/sorter performs. e.g. DIVERT_LEFT to push HUs off to the left, DWS to capture dimensions/weight, QUERY_POINT to hold an HU until released. Leave none selected for plain transport.')}
                example="DIVERT_LEFT"
              />
            </>
          }
        >
          <ProcessTypesPicker
            value={d.processTypes ?? []}
            onChange={(next) => setD({ ...d, processTypes: next })}
          />
        </Field>
      )}

      {showStorageAreas && (
        <Field
          label={
            <>
              {t('fldStorageAreas', 'Storage areas')}{' '}
              <InfoTip
                text={t('tipStorageAreas', "Storage areas (blocks) served by this ASRS, links them via the block's equipment.")}
                example="ASRS-A01, ASRS-A02"
              />
            </>
          }
        >
          <StorageAreasPicker
            blocks={blocks}
            loading={blocksLoading}
            error={blocksError}
            selected={linked}
            onToggle={toggleBlock}
          />
        </Field>
      )}

      <Field label={<>{t('fldVendor', 'Vendor')} <InfoTip text={t('tipVendor', 'Manufacturer or supplier of the equipment.')} example="Dematic" /></>}>
        <input
          className="form-control"
          value={d.vendor ?? ''}
          onChange={(e) => setD({ ...d, vendor: e.target.value })}
        />
      </Field>
      <Field label={<>{t('fldModel', 'Model')} <InfoTip text={t('tipModel', "Vendor's model name or number for this equipment.")} example="Multishuttle 2" /></>}>
        <input
          className="form-control"
          value={d.model ?? ''}
          onChange={(e) => setD({ ...d, model: e.target.value })}
        />
      </Field>
      <Field label={<>{t('fldAdapterEndpoint', 'Adapter endpoint')} <InfoTip text={t('tipAdapterEndpoint', "URL the WCS device adapter uses to talk to this equipment's controller.")} example="http://device-adapter:8080" /></>}>
        <input
          className="form-control"
          value={d.adapterEndpoint ?? ''}
          placeholder="e.g. http://device-adapter:8080"
          onChange={(e) => setD({ ...d, adapterEndpoint: e.target.value })}
        />
      </Field>
      <Field label={<>{t('fldStatus', 'Status')} <InfoTip text={t('tipStatusEquipment', 'Lifecycle state: ACTIVE is online, INACTIVE is offline, ARCHIVED is retired.')} example="ACTIVE" /></>}>
        <StatusSelect value={d.status} onChange={(v) => setD({ ...d, status: v })} />
      </Field>
    </EditDialog>
  )
}

// =========================================================================
// Handling unit types (global)
// =========================================================================

function HandlingUnitTypesTab() {
  const t = useT('masterdata')
  const { roles } = useAuth()
  const canWrite = useMdWrite()
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
        setError(`${t('cannotArchive', 'Cannot archive:')} ${active} ${t('activeHuUseType', 'active handling unit(s) still use this type.')}`)
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
      <Toolbar label={t('lblHuType', 'handling unit type')} onAdd={() => setEditing(blank)}>
        <label className="md-check">
          <input
            type="checkbox"
            checked={showArchived}
            onChange={(e) => setShowArchived(e.target.checked)}
          />
          {t('showArchived', 'Show archived')}
        </label>
      </Toolbar>
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={visible}
        rowKey={(h) => h.id ?? h.name}
        rowClassName={(h) => (isArchived(h) ? 'md-row-archived' : '')}
        search={(h) => `${h.name} ${h.status ?? ''}`}
        searchPlaceholder={t('searchHuTypes', 'Search handling unit types…')}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={loading ? t('loading', 'Loading…') : t('noHuTypes', 'No handling unit types yet.')}
        columns={[
          { key: 'name', header: t('colName', 'Name'), sortable: true, sortValue: (h) => h.name ?? '', render: (h) => h.name },
          {
            key: 'dimensions',
            header: t('colDimensions', 'Dimensions'),
            render: (h) => `${h.lengthMm ?? '·'}×${h.widthMm ?? '·'}×${h.heightMm ?? '·'} mm`,
          },
          {
            key: 'weightLimitG',
            header: t('colWeightLimit', 'Weight limit'),
            sortable: true,
            sortValue: (h) => h.weightLimitG ?? 0,
            render: (h) => (h.weightLimitG != null ? `${h.weightLimitG} g` : '—'),
          },
          {
            key: 'nestable',
            header: t('colNestable', 'Nestable'),
            sortable: true,
            sortValue: (h) => (h.nestable ? 1 : 0),
            render: (h) => (h.nestable ? t('yes', 'Yes') : t('no', 'No')),
          },
          {
            key: 'compartments',
            header: t('colCompartments', 'Compartments'),
            sortable: true,
            sortValue: (h) => h.compartments ?? 0,
            render: (h) => h.compartments,
          },
          {
            key: 'storableInAutomation',
            header: t('colAutomation', 'Automation'),
            sortable: true,
            sortValue: (h) => (h.storableInAutomation ? 1 : 0),
            render: (h) => (h.storableInAutomation ? t('yes', 'Yes') : t('no', 'No')),
          },
          {
            key: 'transportableOnConveyor',
            header: t('colConveyor', 'Conveyor'),
            sortable: true,
            sortValue: (h) => (h.transportableOnConveyor ? 1 : 0),
            render: (h) => (h.transportableOnConveyor ? t('yes', 'Yes') : t('no', 'No')),
          },
          {
            key: 'status',
            header: t('colStatus', 'Status'),
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
                  {canWrite ? t('edit', 'Edit') : t('view', 'View')}
                </button>
                {isAdmin && canWrite &&
                  (isArchived(h) ? (
                    <button
                      className="btn btn-ghost btn-sm"
                      disabled={busyId === h.id}
                      onClick={() => restore(h)}
                    >
                      {busyId === h.id ? <span className="spin" /> : t('restore', 'Restore')}
                    </button>
                  ) : (
                    <button
                      className="btn btn-danger btn-sm"
                      disabled={busyId === h.id}
                      onClick={() => archive(h)}
                    >
                      {busyId === h.id ? <span className="spin" /> : t('archive', 'Archive')}
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
  const t = useT('masterdata')
  const [d, setD] = useState<HandlingUnitType>(initial)
  const valid = d.name.trim() !== '' && d.compartments >= 1 && d.compartments <= 8
  return (
    <EditDialog
      title={initial.id ? t('editHuType', 'Edit handling unit type') : t('newHuType', 'New handling unit type')}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (h) => {
        if (h.id) await updateHandlingUnitType(h.id, h)
        else await createHandlingUnitType(h)
        onSaved()
      }}
    >
      <Field label={<>{t('fldName', 'Name')} <InfoTip text={t('tipHuName', 'Unique name of this handling-unit type (carrier/container kind).')} example="TOTE" /></>} required>
        <input className="form-control" value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} />
      </Field>
      <div className="md-grid-3">
        <Field label={<>{t('fldLengthMm', 'Length (mm)')} <InfoTip text={t('tipHuLength', 'External length of the handling unit in millimetres.')} example="600" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.lengthMm ?? ''}
            onChange={(e) => setD({ ...d, lengthMm: num(e.target.value) ?? undefined })}
          />
        </Field>
        <Field label={<>{t('fldWidthMm', 'Width (mm)')} <InfoTip text={t('tipHuWidth', 'External width of the handling unit in millimetres.')} example="400" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.widthMm ?? ''}
            onChange={(e) => setD({ ...d, widthMm: num(e.target.value) ?? undefined })}
          />
        </Field>
        <Field label={<>{t('fldHeightMm', 'Height (mm)')} <InfoTip text={t('tipHuHeight', 'External height of the handling unit in millimetres.')} example="320" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.heightMm ?? ''}
            onChange={(e) => setD({ ...d, heightMm: num(e.target.value) ?? undefined })}
          />
        </Field>
      </div>
      <div className="md-grid-2">
        <Field label={<>{t('fldWeightLimit', 'Weight limit (g)')} <InfoTip text={t('tipWeightLimit', 'Maximum gross weight this handling unit may carry, in grams.')} example="30000" /></>}>
          <input
            className="form-control"
            type="number"
            value={d.weightLimitG ?? ''}
            onChange={(e) => setD({ ...d, weightLimitG: num(e.target.value) ?? undefined })}
          />
        </Field>
        <Field label={<>{t('fldCompartments', 'Compartments (1–8)')} <InfoTip text={t('tipCompartments', 'Number of separate compartments/cells the handling unit is divided into.')} example="4" /></>}>
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
          {t('nestable', 'Nestable')}{' '}
          <InfoTip text={t('tipNestable', 'When on, empty units of this type can stack inside each other to save space.')} example="on for stackable totes" />
        </label>
        <label className="md-check">
          <input
            type="checkbox"
            checked={d.storableInAutomation}
            onChange={(e) => setD({ ...d, storableInAutomation: e.target.checked })}
          />
          {t('storableInAutomation', 'Storable in automation')}{' '}
          <InfoTip text={t('tipStorableInAutomation', 'When on, this type may be stored inside automated systems (ASRS, AutoStore, shuttle grids).')} example="on for a tote, off for a pallet" />
        </label>
        <label className="md-check">
          <input
            type="checkbox"
            checked={d.transportableOnConveyor}
            onChange={(e) => setD({ ...d, transportableOnConveyor: e.target.checked })}
          />
          {t('transportableOnConveyor', 'Transportable on conveyor')}{' '}
          <InfoTip text={t('tipTransportableOnConveyor', 'When on, this type can be moved on the conveyor network.')} example="on for a tote or carton" />
        </label>
      </div>
    </EditDialog>
  )
}

// =========================================================================
// Label templates (global)
// =========================================================================

function LabelTemplatesTab() {
  const t = useT('masterdata')
  const canWrite = useMdWrite()
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
      <Toolbar label={t('lblTemplate', 'template')} onAdd={() => setEditing(blank)} />
      {error && <div className="alert alert-danger">{error}</div>}
      <DataTable
        rows={rows}
        rowKey={(lt) => lt.id ?? lt.code}
        search={(lt) => `${lt.code} ${lt.name ?? ''} ${lt.status ?? ''}`}
        searchPlaceholder={t('searchLabelTemplates', 'Search label templates…')}
        initialSort={{ key: 'code', dir: 'asc' }}
        empty={loading ? t('loading', 'Loading…') : t('noLabelTemplates', 'No label templates yet.')}
        columns={[
          { key: 'code', header: t('colCode', 'Code'), sortable: true, sortValue: (lt) => lt.code ?? '', render: (lt) => lt.code },
          {
            key: 'name',
            header: t('colName', 'Name'),
            sortable: true,
            sortValue: (lt) => lt.name ?? '',
            render: (lt) => lt.name || '—',
          },
          {
            key: 'size',
            header: t('colSizeMm', 'Size (mm)'),
            render: (lt) => `${lt.widthMm} × ${lt.heightMm}`,
          },
          {
            key: 'dpi',
            header: t('colDpi', 'DPI'),
            sortable: true,
            sortValue: (lt) => lt.dpi ?? 0,
            render: (lt) => lt.dpi,
          },
          {
            key: 'elements',
            header: t('colElements', 'Elements'),
            sortable: true,
            sortValue: (lt) => lt.elements?.length ?? 0,
            render: (lt) => lt.elements?.length ?? 0,
          },
          {
            key: 'status',
            header: t('colStatus', 'Status'),
            sortable: true,
            sortValue: (lt) => lt.status ?? '',
            render: (lt) => <StatusBadge status={lt.status} />,
          },
          {
            key: 'actions',
            header: '',
            render: (lt) => (
              <div className="md-row-actions">
                <button className="btn btn-ghost btn-sm" onClick={() => setEditing(lt)}>
                  {canWrite ? t('edit', 'Edit') : t('view', 'View')}
                </button>
                {canWrite && (
                  <button className="btn btn-danger btn-sm" onClick={() => setDeleting(lt)}>
                    {t('delete', 'Delete')}
                  </button>
                )}
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
  const t = useT('masterdata')
  const [d, setD] = useState<LabelTemplate>(initial)
  const valid = d.code.trim() !== '' && d.widthMm > 0 && d.heightMm > 0 && d.dpi > 0
  return (
    <EditDialog
      title={initial.id ? t('editLabelTemplate', 'Edit label template') : t('newLabelTemplate', 'New label template')}
      draft={d}
      canSave={valid}
      onClose={onClose}
      onSave={async (lt) => {
        if (lt.id) await updateLabelTemplate(lt.id, lt)
        else await createLabelTemplate(lt)
        onSaved()
      }}
    >
      <Field label={<>{t('fldCode', 'Code')} <InfoTip text={t('tipLtCode', 'Unique code identifying this label template.')} example="HU-LABEL-100x150" /></>} required>
        <input className="form-control" value={d.code} onChange={(e) => setD({ ...d, code: e.target.value })} />
      </Field>
      <Field label={<>{t('fldName', 'Name')} <InfoTip text={t('tipLtName', 'Human-readable name for the label template.')} example="Tote label 100×150" /></>}>
        <input
          className="form-control"
          value={d.name ?? ''}
          onChange={(e) => setD({ ...d, name: e.target.value })}
        />
      </Field>
      <div className="md-grid-3">
        <Field label={<>{t('fldWidthMm', 'Width (mm)')} <InfoTip text={t('tipLtWidth', 'Printed label width in millimetres.')} example="100" /></>} required>
          <input
            className="form-control"
            type="number"
            value={d.widthMm}
            onChange={(e) => setD({ ...d, widthMm: num(e.target.value) ?? 0 })}
          />
        </Field>
        <Field label={<>{t('fldHeightMm', 'Height (mm)')} <InfoTip text={t('tipLtHeight', 'Printed label height in millimetres.')} example="150" /></>} required>
          <input
            className="form-control"
            type="number"
            value={d.heightMm}
            onChange={(e) => setD({ ...d, heightMm: num(e.target.value) ?? 0 })}
          />
        </Field>
        <Field label={<>{t('fldDpi', 'DPI')} <InfoTip text={t('tipDpi', 'Printer resolution in dots per inch the template is designed for.')} example="203" /></>} required>
          <input
            className="form-control"
            type="number"
            value={d.dpi}
            onChange={(e) => setD({ ...d, dpi: num(e.target.value) ?? 203 })}
          />
        </Field>
      </div>
      <p className="muted" style={{ fontSize: '.8rem' }}>
        {d.elements?.length ?? 0} {t('elementsSuffix', 'element(s). The visual element designer lives in a future iteration; element data is preserved on save.')}
      </p>
      <Field label={<>{t('fldStatus', 'Status')} <InfoTip text={t('tipStatusLabel', 'Lifecycle state: ACTIVE is selectable for printing, INACTIVE is hidden, ARCHIVED is retired.')} example="ACTIVE" /></>}>
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
