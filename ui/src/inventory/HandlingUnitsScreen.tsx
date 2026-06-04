import { useCallback, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { useWarehouse } from '../warehouse/WarehouseContext'
import Select from '../ui/Select'
import DataTable from '../ui/DataTable'
import { HandlingUnitType, Location, listHandlingUnitTypes, listLocations } from '../masterdata/api'
import {
  HandlingUnit,
  HandlingUnitStatus,
  createHandlingUnit,
  listHandlingUnits,
  updateHandlingUnit,
} from './api'

// ---------------------------------------------------------------------------
// Handling units registry — the physical containers (cartons, pallets, totes…)
// that hold stock. Scoped to the warehouse selected in the top bar. Each HU has a
// barcode (code), a type, a current location and a status.
// ---------------------------------------------------------------------------

const HU_STATUSES: HandlingUnitStatus[] = ['ACTIVE', 'EMPTY', 'IN_TRANSIT', 'RETIRED']

function errMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

function StatusBadge({ status }: { status?: string }) {
  const s = (status ?? '').toUpperCase()
  const cls =
    s === 'ACTIVE' ? 'badge-success' : s === 'RETIRED' ? 'badge-danger' : 'badge-warning'
  return <span className={`badge ${cls}`}>{status ?? '—'}</span>
}

export default function HandlingUnitsScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [rows, setRows] = useState<HandlingUnit[]>([])
  const [types, setTypes] = useState<HandlingUnitType[]>([])
  const [locations, setLocations] = useState<Location[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<HandlingUnit | null>(null)

  const load = useCallback(async () => {
    if (!warehouseId) {
      setRows([])
      return
    }
    setLoading(true)
    try {
      setError(null)
      const [hus, hts, locs] = await Promise.all([
        listHandlingUnits(warehouseId),
        listHandlingUnitTypes(),
        listLocations(warehouseId),
      ])
      setRows(hus)
      setTypes(hts)
      setLocations(locs)
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setLoading(false)
    }
  }, [warehouseId])

  useEffect(() => {
    load()
  }, [load])

  const typeName = (id?: string | null) => types.find((t) => t.id === id)?.name ?? (id ? '—' : '—')
  const locationCode = (id?: string | null) => locations.find((l) => l.id === id)?.code ?? (id ? '—' : '—')

  const blank: HandlingUnit = {
    warehouseId,
    code: '',
    huTypeId: null,
    locationId: null,
    status: 'ACTIVE',
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <span className="eyebrow">Operations</span>
        <h1>Handling units</h1>
        <p>
          Registry of physical handling units (cartons, pallets, totes) — code, type, location and
          status. Scoped to the warehouse selected in the top bar.
        </p>
      </div>

      {!warehouseId ? (
        <div className="glass card-pad">
          <div className="alert">Select a warehouse above to view its handling units.</div>
        </div>
      ) : (
        <div className="glass card-pad hu-panel">
          <div className="toolbar">
            <div className="spacer" />
            <button className="btn btn-primary btn-sm" onClick={() => setEditing(blank)}>
              + Register handling unit
            </button>
          </div>
          {error && <div className="alert alert-danger">{error}</div>}
          <DataTable
            rows={rows}
            rowKey={(h) => h.huId ?? h.code}
            search={(h) => `${h.code} ${typeName(h.huTypeId)} ${locationCode(h.locationId)} ${h.status}`}
            searchPlaceholder="Search handling units…"
            initialSort={{ key: 'code', dir: 'asc' }}
            empty={loading ? 'Loading…' : 'No handling units for this warehouse.'}
            columns={[
              { key: 'code', header: 'Code', sortable: true, sortValue: (h) => h.code ?? '', render: (h) => <code>{h.code}</code> },
              {
                key: 'type',
                header: 'Type',
                sortable: true,
                sortValue: (h) => typeName(h.huTypeId),
                render: (h) => typeName(h.huTypeId),
              },
              {
                key: 'location',
                header: 'Location',
                sortable: true,
                sortValue: (h) => locationCode(h.locationId),
                render: (h) => locationCode(h.locationId),
              },
              {
                key: 'status',
                header: 'Status',
                sortable: true,
                sortValue: (h) => h.status ?? '',
                render: (h) => <StatusBadge status={h.status} />,
              },
              {
                key: 'actions',
                header: '',
                render: (h) => (
                  <div className="hu-row-actions">
                    <button className="btn btn-ghost btn-sm" onClick={() => setEditing(h)}>
                      Edit
                    </button>
                  </div>
                ),
              },
            ]}
          />
        </div>
      )}

      {editing && (
        <HandlingUnitDialog
          initial={{ ...editing, warehouseId }}
          types={types}
          locations={locations}
          onClose={() => setEditing(null)}
          onSaved={load}
        />
      )}

      <Styles />
    </div>
  )
}

function HandlingUnitDialog({
  initial,
  types,
  locations,
  onClose,
  onSaved,
}: {
  initial: HandlingUnit
  types: HandlingUnitType[]
  locations: Location[]
  onClose: () => void
  onSaved: () => void
}) {
  const [d, setD] = useState<HandlingUnit>(initial)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const valid = d.code.trim() !== ''

  async function save() {
    setSaving(true)
    setError(null)
    try {
      if (d.huId) await updateHandlingUnit(d.huId, d)
      else await createHandlingUnit(d)
      onSaved()
      onClose()
    } catch (e) {
      setError(errMsg(e))
    } finally {
      setSaving(false)
    }
  }

  return createPortal(
    <div className="modal-backdrop" onClick={onClose}>
      <div className="dialog glass" onClick={(e) => e.stopPropagation()}>
        <h2>{initial.huId ? 'Edit handling unit' : 'Register handling unit'}</h2>
        {error && <div className="alert alert-danger">{error}</div>}
        <div className="hu-form">
          <div className="hu-field">
            <label>
              Code<span style={{ color: '#ff8a80' }}> *</span>
            </label>
            <input
              className="form-control"
              value={d.code}
              placeholder="Barcode / HU identifier"
              onChange={(e) => setD({ ...d, code: e.target.value })}
            />
          </div>
          <div className="hu-field">
            <label>Type</label>
            <Select
              ariaLabel="Handling unit type"
              value={d.huTypeId ?? ''}
              onChange={(v) => setD({ ...d, huTypeId: v || null })}
              options={[
                { value: '', label: '— None —' },
                ...types.map((t) => ({ value: t.id ?? '', label: t.name })),
              ]}
            />
          </div>
          <div className="hu-field">
            <label>Location</label>
            <Select
              ariaLabel="Location"
              value={d.locationId ?? ''}
              onChange={(v) => setD({ ...d, locationId: v || null })}
              options={[
                { value: '', label: '— None —' },
                ...locations.map((l) => ({ value: l.id ?? '', label: l.code })),
              ]}
            />
          </div>
          <div className="hu-field">
            <label>Status</label>
            <Select
              ariaLabel="Status"
              value={d.status}
              onChange={(v) => setD({ ...d, status: v as HandlingUnitStatus })}
              options={HU_STATUSES.map((s) => ({ value: s, label: s }))}
            />
          </div>
        </div>
        <div className="dialog-actions">
          <button className="btn btn-ghost btn-sm" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button className="btn btn-primary btn-sm" onClick={save} disabled={saving || !valid}>
            {saving ? <span className="spin" /> : 'Save'}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  )
}

function Styles() {
  return (
    <style>{`
      .hu-panel { margin-bottom: 1rem; }
      .hu-row-actions { display: flex; gap: .4rem; justify-content: flex-end; white-space: nowrap; }
      .hu-form { display: flex; flex-direction: column; gap: .85rem; }
      .hu-field { display: flex; flex-direction: column; }
    `}</style>
  )
}
