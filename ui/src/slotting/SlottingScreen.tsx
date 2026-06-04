import { useEffect, useState } from 'react'
import { useWarehouse } from '../warehouse/WarehouseContext'
import Select from '../ui/Select'
import InfoTip from '../ui/InfoTip'
import {
  PickSlot,
  StorageBlock,
  StorageProfile,
  createPickSlot,
  createStorageProfile,
  deletePickSlot,
  deleteStorageProfile,
  listPickSlots,
  listStorageBlocks,
  listStorageProfiles,
} from './api'

// Slotting admin (ADR 0003): assign SKU+UoM to fixed pick faces (manual racking / pick-by-light)
// with min/max, and assign SKUs to automated storage blocks (ASRS / AutoStore / AMR-GTP pools).
const cell: React.CSSProperties = { padding: '4px 8px', borderBottom: '1px solid #eee', fontSize: 13 }
const input: React.CSSProperties = { padding: '4px 6px', width: 120 }

export default function SlottingScreen() {
  const { currentWarehouseId: warehouseId } = useWarehouse()
  const [pickSlots, setPickSlots] = useState<PickSlot[]>([])
  const [profiles, setProfiles] = useState<StorageProfile[]>([])
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [error, setError] = useState<string | null>(null)

  async function refresh() {
    if (!warehouseId) return
    try {
      setError(null)
      const [ps, sp, bl] = await Promise.all([
        listPickSlots(warehouseId),
        listStorageProfiles(warehouseId),
        listStorageBlocks(warehouseId),
      ])
      setPickSlots(ps)
      setProfiles(sp)
      setBlocks(bl)
    } catch (e) {
      setError(String(e))
    }
  }

  useEffect(() => {
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [warehouseId])

  return (
    <div style={{ padding: '1rem', overflow: 'auto', height: '100%' }}>
      {error && (
        <div style={{ marginBottom: 16 }}>
          <span style={{ color: '#c0392b' }}>{error}</span>
        </div>
      )}

      <PickFaces warehouseId={warehouseId} slots={pickSlots} onChange={refresh} />
      <BlockSlotting warehouseId={warehouseId} profiles={profiles} blocks={blocks} onChange={refresh} />
    </div>
  )
}

function PickFaces({ warehouseId, slots, onChange }: { warehouseId: string; slots: PickSlot[]; onChange: () => void }) {
  const [form, setForm] = useState({ locationId: '', skuId: '', uomId: '', minQty: 0, maxQty: 0, directToPick: false })

  async function add() {
    await createPickSlot({ warehouseId, ...form })
    setForm({ locationId: '', skuId: '', uomId: '', minQty: 0, maxQty: 0, directToPick: false })
    onChange()
  }

  return (
    <section style={{ marginBottom: 28 }}>
      <h3>Pick faces (manual slotting · min/max)</h3>
      <p style={{ color: '#666', fontSize: 13, marginTop: 0 }}>
        Assign a SKU+UoM to a fixed pick location. Replenishment tops it up to <em>max</em>; inbound may go
        straight here when <em>direct-to-pick</em> is on.
      </p>
      <table style={{ borderCollapse: 'collapse', width: '100%', marginBottom: 8 }}>
        <thead>
          <tr style={{ textAlign: 'left', fontSize: 12, color: '#888' }}>
            <th style={cell}>Location <InfoTip text="The fixed pick location (rack/bin face) this SKU is slotted to. Pickers always go here for this item." example="A-01-03-2" /></th><th style={cell}>SKU <InfoTip text="The stock item assigned to this pick face." example="SKU-100423" /></th><th style={cell}>UoM <InfoTip text="Unit of measure picked from this face — the pick quantity is counted in these units." example="EA" /></th>
            <th style={cell}>Min <InfoTip text="Replenishment trigger: when on-hand at the face drops to or below this, a top-up task is raised." example="12" /></th><th style={cell}>Max <InfoTip text="Target fill level. Replenishment tops the face back up to this quantity." example="48" /></th><th style={cell}>Direct <InfoTip text="When on, inbound stock for this SKU can be put away straight to the pick face instead of to reserve storage." example="on" /></th><th style={cell}></th>
          </tr>
        </thead>
        <tbody>
          {slots.map((s) => (
            <tr key={s.id}>
              <td style={cell}>{s.locationId}</td><td style={cell}>{s.skuId}</td><td style={cell}>{s.uomId}</td>
              <td style={cell}>{s.minQty}</td><td style={cell}>{s.maxQty}</td><td style={cell}>{s.directToPick ? '✓' : ''}</td>
              <td style={cell}><button onClick={async () => { await deletePickSlot(s.id!); onChange() }}>✕</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
        <input style={input} placeholder="location" value={form.locationId} onChange={(e) => setForm({ ...form, locationId: e.target.value })} />
        <input style={input} placeholder="sku" value={form.skuId} onChange={(e) => setForm({ ...form, skuId: e.target.value })} />
        <input style={input} placeholder="uom" value={form.uomId} onChange={(e) => setForm({ ...form, uomId: e.target.value })} />
        <input style={{ ...input, width: 60 }} type="number" placeholder="min" value={form.minQty} onChange={(e) => setForm({ ...form, minQty: Number(e.target.value) })} />
        <input style={{ ...input, width: 60 }} type="number" placeholder="max" value={form.maxQty} onChange={(e) => setForm({ ...form, maxQty: Number(e.target.value) })} />
        <label style={{ fontSize: 13 }}><input type="checkbox" checked={form.directToPick} onChange={(e) => setForm({ ...form, directToPick: e.target.checked })} /> direct-to-pick <InfoTip text="When on, inbound stock for this SKU can be put away straight to the pick face instead of to reserve storage." example="on" /></label>
        <button onClick={add} disabled={!warehouseId || !form.locationId || !form.skuId || !form.uomId}>Add pick face</button>
      </div>
    </section>
  )
}

function BlockSlotting({
  warehouseId, profiles, blocks, onChange,
}: { warehouseId: string; profiles: StorageProfile[]; blocks: StorageBlock[]; onChange: () => void }) {
  const [form, setForm] = useState({ skuId: '', blockId: '', velocityClass: 'B', consolidate: true, minAisles: 1, maxAislePct: 0.5 })

  async function add() {
    await createStorageProfile({ warehouseId, ...form })
    setForm({ skuId: '', blockId: '', velocityClass: 'B', consolidate: true, minAisles: 1, maxAislePct: 0.5 })
    onChange()
  }

  const blockLabel = (id: string) => {
    const b = blocks.find((x) => x.id === id)
    return b ? `${b.code} (${b.storageType})` : id
  }

  return (
    <section>
      <h3>Block slotting (automated ASRS / AutoStore / AMR-GTP)</h3>
      <p style={{ color: '#666', fontSize: 13, marginTop: 0 }}>
        Assign a SKU to a storage block (the whole pool, all aisles). The put-away engine chooses the actual
        location per HU, balancing velocity-to-exit, same-SKU consolidation, aisle redundancy and fill balance.
      </p>
      <table style={{ borderCollapse: 'collapse', width: '100%', marginBottom: 8 }}>
        <thead>
          <tr style={{ textAlign: 'left', fontSize: 12, color: '#888' }}>
            <th style={cell}>SKU <InfoTip text="The stock item being assigned to an automated storage block (ASRS / AutoStore / AMR-GTP pool)." example="SKU-100423" /></th><th style={cell}>Block <InfoTip text="The storage block (whole pool, all aisles) this SKU may be stored in. The put-away engine picks the exact location per HU." example="ASRS-1 (asrs)" /></th><th style={cell}>Velocity <InfoTip text="Movement class driving how close to the exit/pick the SKU is stored. A = fast mover, C = slow mover." example="A" /></th>
            <th style={cell}>Consolidate <InfoTip text="When on, the engine prefers placing the same SKU together (fewer, denser locations) rather than spreading it out." example="on" /></th><th style={cell}>Min aisles <InfoTip text="Minimum number of distinct aisles this SKU must be spread across, for redundancy if an aisle goes offline." example="2" /></th><th style={cell}>Max aisle % <InfoTip text="Cap on the fraction of one aisle a single SKU may occupy, to keep aisles balanced (0–1)." example="0.5" /></th><th style={cell}></th>
          </tr>
        </thead>
        <tbody>
          {profiles.map((p) => (
            <tr key={p.id}>
              <td style={cell}>{p.skuId}</td><td style={cell}>{blockLabel(p.blockId)}</td><td style={cell}>{p.velocityClass}</td>
              <td style={cell}>{p.consolidate ? '✓' : ''}</td><td style={cell}>{p.minAisles}</td><td style={cell}>{p.maxAislePct}</td>
              <td style={cell}><button onClick={async () => { await deleteStorageProfile(p.id!); onChange() }}>✕</button></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
        <input style={input} placeholder="sku" value={form.skuId} onChange={(e) => setForm({ ...form, skuId: e.target.value })} />
        <Select
          ariaLabel="Block"
          placeholder="block…"
          style={input}
          value={form.blockId}
          onChange={(v) => setForm({ ...form, blockId: v })}
          options={[
            { value: '', label: 'block…' },
            ...blocks.map((b) => ({ value: b.id, label: `${b.code} (${b.storageType})` })),
          ]}
        />
        <Select
          ariaLabel="Velocity class"
          style={{ ...input, width: 70 }}
          value={form.velocityClass}
          onChange={(v) => setForm({ ...form, velocityClass: v })}
          options={['A', 'B', 'C'].map((c) => ({ value: c, label: c }))}
        />
        <label style={{ fontSize: 13 }}><input type="checkbox" checked={form.consolidate} onChange={(e) => setForm({ ...form, consolidate: e.target.checked })} /> consolidate <InfoTip text="When on, the engine prefers placing the same SKU together (fewer, denser locations) rather than spreading it out." example="on" /></label>
        <input style={{ ...input, width: 70 }} type="number" placeholder="min aisles" value={form.minAisles} onChange={(e) => setForm({ ...form, minAisles: Number(e.target.value) })} />
        <input style={{ ...input, width: 80 }} type="number" step="0.1" placeholder="max aisle %" value={form.maxAislePct} onChange={(e) => setForm({ ...form, maxAislePct: Number(e.target.value) })} />
        <button onClick={add} disabled={!warehouseId || !form.skuId || !form.blockId}>Add block slotting</button>
      </div>
    </section>
  )
}
