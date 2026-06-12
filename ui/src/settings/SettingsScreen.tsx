import type { ReactNode } from 'react'
import { useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import InfoTip from '../ui/InfoTip'
import Select from '../ui/Select'
import styles from './settings.module.css'
import {
  AdapterInfo,
  BlockPolicy,
  CountSchedule,
  DemoResult,
  DemoStatus,
  EmulatorStatus,
  FulfillmentConfig,
  HealthStatus,
  Shipper,
  StorageBlock,
  Warehouse,
  archiveShipper,
  createSchedule,
  createShipper,
  defaultBlockPolicy,
  defaultFulfillmentConfig,
  disableDemo,
  disableEmulator,
  enableDemo,
  enableEmulator,
  generateDueTasks,
  getBlockPolicy,
  getDemoStatus,
  getEmulatorStatus,
  getFulfillmentConfig,
  getGatewayHealth,
  listAdapters,
  listSchedules,
  listShippers,
  listStorageBlocks,
  listWarehouses,
  saveBlockPolicy,
  saveFulfillmentConfig,
  updateShipper,
} from './api'

type Tab = 'slotting' | 'cubing' | 'counting' | 'integration' | 'system' | 'demo' | 'emulator'

const TABS: { key: Tab; label: string }[] = [
  { key: 'slotting', label: 'Slotting policy' },
  { key: 'cubing', label: 'Cubing' },
  { key: 'counting', label: 'Counting' },
  { key: 'integration', label: 'Integrations' },
  { key: 'system', label: 'System status' },
  { key: 'demo', label: 'Demo mode' },
  { key: 'emulator', label: 'Hardware emulator' },
]

// Settings / Configuration (ADMIN). A UI-only console over existing service endpoints:
// per-block put-away scoring (slotting), ABC-cadence count schedules (counting),
// host/adapter config + status (read-only), and edge security / health (read-only).
export default function SettingsScreen() {
  const [tab, setTab] = useState<Tab>('slotting')
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [warehouseId, setWarehouseId] = useState('')
  const [whError, setWhError] = useState<string | null>(null)

  useEffect(() => {
    listWarehouses()
      .then((list) => {
        setWarehouses(list)
        if (list.length) setWarehouseId(list[0].id)
      })
      .catch((e) => setWhError(String(e)))
  }, [])

  const needsWarehouse = tab === 'slotting' || tab === 'cubing' || tab === 'counting' || tab === 'demo'

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">openWCS · Configuration</div>
        <h1>Settings</h1>
        <p>System policy, schedules and integration endpoints. Changes write directly to the live services.</p>
      </div>

      {whError && <div className="alert-danger" style={{ marginBottom: '1rem' }}>Could not load warehouses: {whError}</div>}

      <div className={styles.layout}>
        <nav className={`glass ${styles.nav}`}>
          {TABS.map((t) => (
            <button
              key={t.key}
              type="button"
              className={`${styles.navItem} ${tab === t.key ? styles.active : ''}`}
              onClick={() => setTab(t.key)}
            >
              {t.label}
            </button>
          ))}
        </nav>

        <div className={styles.panel}>
          {needsWarehouse && (
            <div className={`glass ${styles.section}`}>
              <div className={styles.field} style={{ maxWidth: 360 }}>
                <label htmlFor="wh">Warehouse <InfoTip text="The warehouse the policies and schedules on this page apply to. All edits below are scoped to this selection." example="WH01 — Central DC" /></label>
                <Select
                  ariaLabel="Warehouse"
                  value={warehouseId}
                  onChange={(v) => setWarehouseId(v)}
                  options={
                    warehouses.length === 0
                      ? [{ value: '', label: 'No warehouses found' }]
                      : warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` }))
                  }
                />
                <span className={styles.fieldHint}>Policies and schedules below apply to this warehouse.</span>
              </div>
            </div>
          )}

          {tab === 'slotting' && <SlottingPolicy warehouseId={warehouseId} />}
          {tab === 'cubing' && <CubingSettings warehouseId={warehouseId} />}
          {tab === 'counting' && <CountingSettings warehouseId={warehouseId} />}
          {tab === 'integration' && <Integrations />}
          {tab === 'system' && <SystemStatus />}
          {tab === 'demo' && <DemoMode warehouseId={warehouseId} />}
          {tab === 'emulator' && <EmulatorMode />}
        </div>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ slotting

function NumberField({
  label,
  value,
  step,
  hint,
  onChange,
}: {
  label: ReactNode
  value: number
  step?: string
  hint?: string
  onChange: (v: number) => void
}) {
  return (
    <div className={styles.field}>
      <label>{label}</label>
      <input
        className="form-control"
        type="number"
        step={step || 'any'}
        value={Number.isFinite(value) ? value : ''}
        onChange={(e) => onChange(Number(e.target.value))}
      />
      {hint && <span className={styles.fieldHint}>{hint}</span>}
    </div>
  )
}

function Toggle({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className={styles.toggle}>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span className={styles.slider} />
    </label>
  )
}

function SlottingPolicy({ warehouseId }: { warehouseId: string }) {
  const [blocks, setBlocks] = useState<StorageBlock[]>([])
  const [blockId, setBlockId] = useState('')
  const [policy, setPolicy] = useState<BlockPolicy | null>(null)
  const [isNew, setIsNew] = useState(false)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setBlocks([])
    setBlockId('')
    setPolicy(null)
    if (!warehouseId) return
    listStorageBlocks(warehouseId)
      .then((bl) => {
        setBlocks(bl)
        if (bl.length) setBlockId(bl[0].id)
      })
      .catch((e) => setError(String(e)))
  }, [warehouseId])

  useEffect(() => {
    if (!blockId) {
      setPolicy(null)
      return
    }
    setLoading(true)
    setError(null)
    setSaved(false)
    getBlockPolicy(blockId)
      .then((p) => {
        if (p) {
          setPolicy(p)
          setIsNew(false)
        } else {
          setPolicy(defaultBlockPolicy(warehouseId))
          setIsNew(true)
        }
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false))
  }, [blockId, warehouseId])

  function patch(p: Partial<BlockPolicy>) {
    setPolicy((cur) => (cur ? { ...cur, ...p } : cur))
    setSaved(false)
  }

  async function save() {
    if (!policy || !blockId) return
    setSaving(true)
    setError(null)
    try {
      const out = await saveBlockPolicy(blockId, { ...policy, warehouseId })
      setPolicy(out)
      setIsNew(false)
      setSaved(true)
    } catch (e) {
      setError(String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className={`glass ${styles.section}`}>
      <div className={styles.sectionHead}>
        <div>
          <h2>Put-away scoring policy</h2>
          <p>
            Per-block weights the put-away engine combines into one location score, plus aisle redundancy
            and re-slotting controls. Tuning the weights dials the consolidation-vs-redundancy trade-off.
          </p>
        </div>
      </div>

      <div className={styles.field} style={{ maxWidth: 360, marginBottom: '1.25rem' }}>
        <label htmlFor="block">Storage block <InfoTip text="The storage block whose put-away scoring policy you are editing. Each block has its own weights and aisle constraints." example="RACK-A (PALLET_RACK)" /></label>
        <Select
          ariaLabel="Storage block"
          value={blockId}
          onChange={(v) => setBlockId(v)}
          options={
            blocks.length === 0
              ? [{ value: '', label: 'No storage blocks for this warehouse' }]
              : blocks.map((b) => ({ value: b.id, label: `${b.code} (${b.storageType})` }))
          }
        />
        {isNew && policy && <span className={styles.fieldHint}>No policy yet — showing defaults; saving creates one.</span>}
      </div>

      {error && <div className="alert-danger" style={{ marginBottom: '1rem' }}>{error}</div>}
      {loading && <p className={styles.muted}>Loading policy…</p>}

      {policy && !loading && (
        <>
          <h3 style={{ margin: '0 0 .5rem', fontSize: '.95rem' }}>Scorer weights</h3>
          <div className={styles.grid}>
            <NumberField label={<>Velocity <InfoTip text="Weight favouring fast-moving SKUs being slotted closer to the exit/dispatch point. Higher means velocity dominates the score." example="2.0" /></>} value={policy.wVelocity} onChange={(v) => patch({ wVelocity: v })} hint="Velocity-to-exit weight" />
            <NumberField label={<>Consolidation <InfoTip text="Weight rewarding placing stock of the same SKU together to reduce fragmentation. Higher means tighter consolidation." example="1.0" /></>} value={policy.wConsolidation} onChange={(v) => patch({ wConsolidation: v })} hint="Same-SKU consolidation" />
            <NumberField label={<>Redundancy <InfoTip text="Weight rewarding spreading a SKU across multiple aisles for picking resilience. Higher trades consolidation for redundancy." example="0.5" /></>} value={policy.wRedundancy} onChange={(v) => patch({ wRedundancy: v })} hint="Aisle redundancy" />
            <NumberField label={<>Balance <InfoTip text="Weight rewarding even fill across locations so no single aisle or zone overflows. Higher levels out utilisation." example="0.75" /></>} value={policy.wBalance} onChange={(v) => patch({ wBalance: v })} hint="Fill balance" />
          </div>

          <h3 style={{ margin: '1.25rem 0 .5rem', fontSize: '.95rem' }}>Aisle constraints</h3>
          <div className={styles.grid}>
            <NumberField label={<>Max aisle % <InfoTip text="Maximum share of a single SKU's stock allowed in one aisle, as a fraction 0–1. Caps concentration so picking stays redundant." example="0.5" /></>} value={policy.defaultMaxAislePct} step="0.05" onChange={(v) => patch({ defaultMaxAislePct: v })} hint="Cap of one SKU per aisle (0–1)" />
            <NumberField label={<>Min aisles · A <InfoTip text="Minimum number of distinct aisles a fast-moving A-class SKU should be spread across for picking resilience." example="3" /></>} value={policy.minAislesA} step="1" onChange={(v) => patch({ minAislesA: v })} />
            <NumberField label={<>Min aisles · B <InfoTip text="Minimum number of distinct aisles a medium-velocity B-class SKU should be spread across." example="2" /></>} value={policy.minAislesB} step="1" onChange={(v) => patch({ minAislesB: v })} />
            <NumberField label={<>Min aisles · C <InfoTip text="Minimum number of distinct aisles a slow-moving C-class SKU should be spread across; usually 1 (consolidated)." example="1" /></>} value={policy.minAislesC} step="1" onChange={(v) => patch({ minAislesC: v })} />
          </div>

          <h3 style={{ margin: '1.25rem 0 .5rem', fontSize: '.95rem' }}>Re-slotting</h3>
          <div className={styles.toggleRow} style={{ marginBottom: '1rem' }}>
            <Toggle checked={policy.reslotEnabled} onChange={(v) => patch({ reslotEnabled: v })} />
            <div>
              <div>Automatic re-slotting <InfoTip text="When on, the engine moves stock toward better-scoring slots during off-peak windows. When off, slotting only happens on new put-aways." example="On" /></div>
              <span className={styles.fieldHint}>Move stock toward optimal slots during off-peak windows.</span>
            </div>
          </div>
          <div className={styles.grid}>
            <NumberField
              label={<>Re-slot shift % <InfoTip text="Maximum share of stock the re-slot sweep may relocate in a single run, as a fraction 0–1. Keeps moves gradual." example="0.1" /></>}
              value={policy.reslotShiftPct}
              step="0.05"
              onChange={(v) => patch({ reslotShiftPct: v })}
              hint="Max share of stock moved per run (0–1)"
            />
            <div className={styles.field}>
              <label>Off-peak cron <InfoTip text="Cron expression (sec min hour day month weekday) setting when the re-slot sweep runs, ideally during quiet hours." example="0 0 2 * * *" /></label>
              <input
                className="form-control"
                value={policy.offpeakCron ?? ''}
                placeholder="0 0 2 * * *"
                onChange={(e) => patch({ offpeakCron: e.target.value })}
              />
              <span className={styles.fieldHint}>When the re-slot sweep runs.</span>
            </div>
          </div>

          {(policy.velocityHalfLifeDays != null || policy.abcAShare != null) && (
            <>
              <h3 style={{ margin: '1.25rem 0 .5rem', fontSize: '.95rem' }}>
                Velocity / ABC <span className={styles.muted} style={{ fontWeight: 400, fontSize: '.75rem' }}>(read-only)</span>
              </h3>
              <div className={styles.grid}>
                <ReadOnly label="Velocity half-life (days)" value={policy.velocityHalfLifeDays} />
                <ReadOnly label="ABC · A share" value={policy.abcAShare} />
                <ReadOnly label="ABC · B share" value={policy.abcBShare} />
              </div>
            </>
          )}

          <div className={styles.actions}>
            <button className="btn btn-primary" type="button" onClick={save} disabled={saving || !blockId}>
              {saving ? 'Saving…' : 'Save policy'}
            </button>
            {saved && <span className={styles.savedNote}>Saved.</span>}
          </div>
        </>
      )}
    </section>
  )
}

function ReadOnly({ label, value }: { label: string; value?: number }) {
  return (
    <div className={styles.field}>
      <label>{label}</label>
      <input className="form-control" value={value ?? ''} disabled readOnly />
    </div>
  )
}

// ------------------------------------------------------------------ counting

function CountingSettings({ warehouseId }: { warehouseId: string }) {
  const [schedules, setSchedules] = useState<CountSchedule[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [genNote, setGenNote] = useState<string | null>(null)
  const [form, setForm] = useState({
    name: '',
    scopeType: 'ABC_CLASS',
    abcClass: 'A',
    countType: 'BLIND',
    cadenceDays: 30,
    tolerance: 0,
  })

  async function refresh() {
    if (!warehouseId) {
      setSchedules([])
      return
    }
    try {
      setError(null)
      setSchedules(await listSchedules(warehouseId))
    } catch (e) {
      setError(String(e))
    }
  }

  useEffect(() => {
    refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [warehouseId])

  async function add() {
    if (!warehouseId || !form.name || !form.cadenceDays) return
    setBusy(true)
    setError(null)
    try {
      await createSchedule({
        warehouseId,
        name: form.name,
        scopeType: form.scopeType,
        abcClass: form.scopeType === 'ABC_CLASS' ? form.abcClass : null,
        countType: form.countType,
        cadenceDays: form.cadenceDays,
        tolerance: form.tolerance,
      })
      setForm({ ...form, name: '' })
      await refresh()
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  async function runSweep() {
    setBusy(true)
    setGenNote(null)
    setError(null)
    try {
      const tasks = await generateDueTasks(warehouseId || undefined)
      setGenNote(`Sweep complete — ${tasks.length} count task(s) emitted.`)
      await refresh()
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={`glass ${styles.section}`}>
      <div className={styles.sectionHead}>
        <div>
          <h2>Cycle counting · ABC cadence</h2>
          <p>
            How often each scope is counted. The generator emits a count task when a schedule is due, then
            advances by its cadence (A SKUs short, C SKUs long).
          </p>
        </div>
        <button className="btn btn-ghost btn-sm" type="button" onClick={runSweep} disabled={busy}>
          Run sweep now
        </button>
      </div>

      {error && <div className="alert-danger" style={{ marginBottom: '1rem' }}>{error}</div>}
      {genNote && <p className={styles.savedNote}>{genNote}</p>}

      <table className={styles.table} style={{ marginBottom: '1.25rem' }}>
        <thead>
          <tr>
            <th>Name</th>
            <th>Scope</th>
            <th>Type</th>
            <th>Cadence</th>
            <th>Tolerance</th>
            <th>Next due</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {schedules.length === 0 && (
            <tr>
              <td colSpan={7} className={styles.muted}>
                {warehouseId ? 'No schedules yet.' : 'Select a warehouse.'}
              </td>
            </tr>
          )}
          {schedules.map((s) => (
            <tr key={s.id}>
              <td>{s.name}</td>
              <td>{s.scopeType}{s.abcClass ? ` · ${s.abcClass}` : ''}</td>
              <td>{s.countType}</td>
              <td>{s.cadenceDays}d</td>
              <td>{String(s.tolerance ?? 0)}</td>
              <td>{s.nextDueAt ? new Date(s.nextDueAt).toLocaleDateString() : '—'}</td>
              <td>{s.status}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <h3 style={{ margin: '0 0 .5rem', fontSize: '.95rem' }}>New schedule</h3>
      <div className={styles.grid}>
        <div className={styles.field}>
          <label>Name <InfoTip text="A label for this count schedule so it's easy to recognise in the list and in generated count tasks." example="A-class weekly" /></label>
          <input className="form-control" value={form.name} placeholder="A-class weekly" onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </div>
        <div className={styles.field}>
          <label>Scope <InfoTip text="What this schedule counts: an ABC velocity class, or a specific zone, block, location or SKU. Defines which stock the cadence applies to." example="ABC class" /></label>
          <Select
            ariaLabel="Scope"
            value={form.scopeType}
            onChange={(v) => setForm({ ...form, scopeType: v })}
            options={[
              { value: 'ABC_CLASS', label: 'ABC class' },
              { value: 'ZONE', label: 'Zone' },
              { value: 'BLOCK', label: 'Block' },
              { value: 'LOCATION', label: 'Location' },
              { value: 'SKU', label: 'SKU' },
            ]}
          />
        </div>
        {form.scopeType === 'ABC_CLASS' && (
          <div className={styles.field}>
            <label>ABC class <InfoTip text="Which velocity class to count: A = fast movers (count often), B = medium, C = slow movers (count rarely)." example="A" /></label>
            <Select
              ariaLabel="ABC class"
              value={form.abcClass}
              onChange={(v) => setForm({ ...form, abcClass: v })}
              options={[
                { value: 'A', label: 'A' },
                { value: 'B', label: 'B' },
                { value: 'C', label: 'C' },
              ]}
            />
          </div>
        )}
        <div className={styles.field}>
          <label>Count type <InfoTip text="Blind = counter never sees the expected quantity (most accurate). Variance = counter sees expected qty and confirms or corrects it." example="Blind" /></label>
          <Select
            ariaLabel="Count type"
            value={form.countType}
            onChange={(v) => setForm({ ...form, countType: v })}
            options={[
              { value: 'BLIND', label: 'Blind' },
              { value: 'VARIANCE', label: 'Variance' },
            ]}
          />
        </div>
        <NumberField label={<>Cadence (days) <InfoTip text="How many days between counts for this scope. After a count is emitted the next due date advances by this many days." example="30" /></>} value={form.cadenceDays} step="1" onChange={(v) => setForm({ ...form, cadenceDays: v })} />
        <NumberField label={<>Tolerance <InfoTip text="Accepted variance between counted and expected quantity before a discrepancy is flagged. 0 means any difference counts as a variance." example="0.02" /></>} value={form.tolerance} step="0.01" onChange={(v) => setForm({ ...form, tolerance: v })} hint="Accepted variance" />
      </div>
      <div className={styles.actions}>
        <button className="btn btn-primary" type="button" onClick={add} disabled={busy || !warehouseId || !form.name || !form.cadenceDays}>
          {busy ? 'Working…' : 'Add schedule'}
        </button>
      </div>
    </section>
  )
}

// ------------------------------------------------------------------ integrations

function Integrations() {
  const [adapters, setAdapters] = useState<AdapterInfo[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    listAdapters()
      .then(setAdapters)
      .finally(() => setLoading(false))
  }, [])

  return (
    <section className={`glass ${styles.section}`}>
      <div className={styles.sectionHead}>
        <div>
          <h2>Host &amp; integration endpoints</h2>
          <p>
            The canonical openWCS Host API and the vendor adapters that translate SAP / Manhattan protocols
            into it. Configuration and connectivity are shown read-only.
          </p>
        </div>
      </div>

      {loading && <p className={styles.muted}>Checking adapters…</p>}

      <div className={styles.statusGrid}>
        {adapters.map((a) => (
          <div key={a.key} className={`glass ${styles.statusCard}`}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '.5rem' }}>
              <h3>{a.label}</h3>
              <span className={`${styles.pill} ${a.reachable ? styles.pillOk : styles.pillDown}`}>
                {a.reachable ? 'Reachable' : 'Down'}
              </span>
            </div>
            <p><code className={styles.muted}>{a.path}</code></p>
            {a.service && <p>Service: {a.service}</p>}
            {a.status && <p>Stage: {a.status}</p>}
            {a.description && <p>{a.description}</p>}
            {a.error && <p style={{ color: '#ff8a80' }}>{a.error}</p>}
          </div>
        ))}
      </div>
    </section>
  )
}

// ------------------------------------------------------------------ system status

function SystemStatus() {
  const { session } = useAuth()
  const [health, setHealth] = useState<HealthStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const securityEnabled = !!session?.token

  useEffect(() => {
    getGatewayHealth()
      .then(setHealth)
      .finally(() => setLoading(false))
  }, [])

  const overall = health?.reachable ? (health.status || 'UNKNOWN') : 'UNREACHABLE'
  const overallOk = overall === 'UP'

  return (
    <section className={`glass ${styles.section}`}>
      <div className={styles.sectionHead}>
        <div>
          <h2>System status</h2>
          <p>Edge security mode and gateway health. Read-only.</p>
        </div>
      </div>

      <div className={styles.statusGrid}>
        <div className={`glass ${styles.statusCard}`}>
          <h3>Edge security</h3>
          <p>
            <span className={`${styles.pill} ${securityEnabled ? styles.pillOk : styles.pillWarn}`}>
              {securityEnabled ? 'Enabled' : 'Open'}
            </span>
          </p>
          <p>
            {securityEnabled
              ? 'The gateway is validating JWTs and forwarding identity downstream.'
              : 'No realm token in session — the gateway is permitting all traffic (dev mode).'}
          </p>
          {session?.roles?.length ? <p>Your roles: {session.roles.join(', ')}</p> : null}
        </div>

        <div className={`glass ${styles.statusCard}`}>
          <h3>Gateway health</h3>
          {loading ? (
            <p className={styles.muted}>Checking…</p>
          ) : (
            <p>
              <span className={`${styles.pill} ${overallOk ? styles.pillOk : health?.reachable ? styles.pillWarn : styles.pillDown}`}>
                {overall}
              </span>
            </p>
          )}
          <p><code className={styles.muted}>/actuator/health</code></p>
          {health?.error && <p style={{ color: '#ff8a80' }}>{health.error}</p>}
        </div>

        {health?.components &&
          Object.entries(health.components).map(([name, c]) => (
            <div key={name} className={`glass ${styles.statusCard}`}>
              <h3 style={{ fontSize: '.95rem' }}>{name}</h3>
              <p>
                <span className={`${styles.pill} ${c.status === 'UP' ? styles.pillOk : styles.pillWarn}`}>
                  {c.status || 'UNKNOWN'}
                </span>
              </p>
            </div>
          ))}
      </div>
    </section>
  )
}

// Cubing (per warehouse). The cubing rules (from WarehouseFulfillmentConfig) plus the shipper
// catalog cubing packs into, with each shipper's fill rate and usable volume.
function CubingSettings({ warehouseId }: { warehouseId: string }) {
  const [config, setConfig] = useState<FulfillmentConfig | null>(null)
  const [shippers, setShippers] = useState<Shipper[]>([])
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Shipper | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  function reloadShippers() {
    if (!warehouseId) return
    listShippers(warehouseId)
      .then(setShippers)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }

  async function onArchive(s: Shipper) {
    if (!s.id) return
    setBusyId(s.id)
    setError(null)
    try {
      await archiveShipper(s.id)
      reloadShippers()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyId(null)
    }
  }

  function newShipper(): Shipper {
    return { code: '', shipperType: 'CARTON', maxFillLevel: 1, status: 'ACTIVE', warehouseId }
  }

  useEffect(() => {
    if (!warehouseId) {
      setConfig(null)
      setShippers([])
      return
    }
    setLoading(true)
    setError(null)
    setSaved(false)
    setEditing(null)
    Promise.all([
      getFulfillmentConfig(warehouseId).catch(() => null),
      listShippers(warehouseId).catch(() => [] as Shipper[]),
    ])
      .then(([c, s]) => {
        setConfig(c ?? defaultFulfillmentConfig(warehouseId))
        setShippers(s)
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false))
  }, [warehouseId])

  function patch(p: Partial<FulfillmentConfig>) {
    setConfig((c) => (c ? { ...c, ...p } : c))
    setSaved(false)
  }

  function togglePickType(t: string) {
    if (!config) return
    const has = config.allowedPickTypes.includes(t)
    patch({
      allowedPickTypes: has ? config.allowedPickTypes.filter((x) => x !== t) : [...config.allowedPickTypes, t],
    })
  }

  async function save() {
    if (!config) return
    setSaving(true)
    setError(null)
    try {
      setConfig(await saveFulfillmentConfig(warehouseId, config))
      setSaved(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const active = shippers.filter((s) => s.status !== 'ARCHIVED')

  function fillPct(s: Shipper): string {
    return s.maxFillLevel == null ? '100%' : `${Math.round(s.maxFillLevel * 100)}%`
  }
  function usableVolumeL(s: Shipper): string {
    if (s.lengthMm == null || s.widthMm == null || s.heightMm == null) return '—'
    const litres = (s.lengthMm * s.widthMm * s.heightMm * (s.maxFillLevel ?? 1)) / 1_000_000
    return litres.toFixed(1)
  }

  if (!warehouseId) {
    return (
      <section className={`glass ${styles.section}`}>
        <p className={styles.muted}>Select a warehouse to view its cubing configuration.</p>
      </section>
    )
  }

  return (
    <section className={`glass ${styles.section}`}>
      <div className={styles.sectionHead}>
        <div>
          <h2>Cubing</h2>
          <p>
            How outbound orders are packed into shippers. In APP mode the cubing engine packs each
            order into the active shippers below, largest usable volume first, then downsizes the
            remainder into the smallest shipper that still fits (both volume and net weight). An item
            that does not fit the largest shipper fails the order.
          </p>
        </div>
      </div>

      {error && <div className="alert-danger" style={{ marginBottom: '1rem' }}>{error}</div>}

      {loading || !config ? (
        <p className={styles.muted}>Loading…</p>
      ) : (
        <>
          <div className={styles.grid}>
            <div className={styles.field}>
              <label>
                Cubing mode{' '}
                <InfoTip
                  text="APP packs orders automatically (largest-first bin packing). ONE_TO_ONE uses the host-supplied carton instructions instead."
                  example="APP"
                />
              </label>
              <Select
                ariaLabel="Cubing mode"
                value={config.cubingMode}
                onChange={(v) => patch({ cubingMode: v })}
                options={[
                  { value: 'APP', label: 'APP — automatic bin packing' },
                  { value: 'ONE_TO_ONE', label: 'ONE_TO_ONE — host carton instructions' },
                ]}
              />
            </div>
            <div className={styles.field}>
              <label>
                Default shipper{' '}
                <InfoTip
                  text="Optional fallback shipper for the warehouse. Cubing still chooses per order from all active shippers."
                  example="None"
                />
              </label>
              <Select
                ariaLabel="Default shipper"
                value={config.defaultShipperId ?? ''}
                onChange={(v) => patch({ defaultShipperId: v || null })}
                options={[
                  { value: '', label: 'None' },
                  ...active.map((s) => ({ value: s.id ?? '', label: s.code })),
                ]}
              />
            </div>
          </div>

          <div className={styles.field} style={{ marginTop: '.5rem' }}>
            <label>
              Allowed pick types{' '}
              <InfoTip
                text="Which pick granularities allocation may break an order line down into before cubing."
                example="CASE, SPLIT_CASE, EACH"
              />
            </label>
            <div className={styles.toggleRow}>
              {['CASE', 'SPLIT_CASE', 'EACH'].map((t) => (
                <label key={t} className="md-check">
                  <input
                    type="checkbox"
                    checked={config.allowedPickTypes.includes(t)}
                    onChange={() => togglePickType(t)}
                  />
                  {t.replace(/_/g, ' ')}
                </label>
              ))}
            </div>
          </div>

          <div className={styles.actions}>
            <button className="btn btn-primary" onClick={save} disabled={saving}>
              {saving ? 'Saving…' : 'Save cubing rules'}
            </button>
            {saved && <span className={styles.savedNote}>Saved.</span>}
          </div>

          <div className={styles.sectionHead} style={{ marginTop: '1.5rem' }}>
            <div>
              <h3>Shippers ({active.length} active)</h3>
              <p className={styles.fieldHint}>
                The containers cubing packs into. Fill rate is the usable fraction of inner volume;
                the usable volume column already applies it. Archived shippers are excluded from cubing.
              </p>
            </div>
            <button className="btn btn-outline btn-sm" onClick={() => setEditing(newShipper())}>
              Add shipper
            </button>
          </div>

          {editing && (
            <ShipperForm
              warehouseId={warehouseId}
              shipper={editing}
              onCancel={() => setEditing(null)}
              onSaved={() => {
                setEditing(null)
                reloadShippers()
              }}
            />
          )}

          <table className={styles.table}>
            <thead>
              <tr>
                <th>Code</th>
                <th>Type</th>
                <th>Inner L×W×H (mm)</th>
                <th>Fill rate</th>
                <th>Usable vol (L)</th>
                <th>Tare (g)</th>
                <th>Max gross (g)</th>
                <th>Status</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {shippers.length === 0 ? (
                <tr>
                  <td colSpan={9} className={styles.muted}>
                    No shippers for this warehouse. Add one above, or enable demo mode to seed some.
                  </td>
                </tr>
              ) : (
                [...shippers]
                  .sort(
                    (a, b) =>
                      (a.status === 'ARCHIVED' ? 1 : 0) - (b.status === 'ARCHIVED' ? 1 : 0) ||
                      a.code.localeCompare(b.code),
                  )
                  .map((s) => (
                    <tr key={s.id ?? s.code} style={s.status === 'ARCHIVED' ? { opacity: 0.5 } : undefined}>
                      <td>{s.code}</td>
                      <td>{s.shipperType ?? '—'}</td>
                      <td>
                        {s.lengthMm ?? '·'}×{s.widthMm ?? '·'}×{s.heightMm ?? '·'}
                      </td>
                      <td>{fillPct(s)}</td>
                      <td>{usableVolumeL(s)}</td>
                      <td>{s.tareWeightG ?? '—'}</td>
                      <td>{s.maxWeightG ?? '—'}</td>
                      <td>{s.status ?? 'ACTIVE'}</td>
                      <td style={{ whiteSpace: 'nowrap', textAlign: 'right' }}>
                        <button className="btn btn-ghost btn-sm" onClick={() => setEditing({ ...s })}>
                          Edit
                        </button>{' '}
                        {s.status !== 'ARCHIVED' && (
                          <button
                            className="btn btn-ghost btn-sm"
                            onClick={() => onArchive(s)}
                            disabled={busyId === s.id}
                          >
                            {busyId === s.id ? '…' : 'Archive'}
                          </button>
                        )}
                      </td>
                    </tr>
                  ))
              )}
            </tbody>
          </table>
        </>
      )}
    </section>
  )
}

// Inline add/edit form for a shipper (cubing container). Fill rate is entered as a percentage and
// stored as a 0..1 fraction.
function ShipperForm({
  warehouseId,
  shipper,
  onCancel,
  onSaved,
}: {
  warehouseId: string
  shipper: Shipper
  onCancel: () => void
  onSaved: () => void
}) {
  const [s, setS] = useState<Shipper>(shipper)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const isNew = !s.id

  function set(p: Partial<Shipper>) {
    setS((cur) => ({ ...cur, ...p }))
  }

  async function save() {
    if (!s.code.trim()) {
      setError('Code is required.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      if (isNew) await createShipper(warehouseId, s)
      else await updateShipper(warehouseId, s.id as string, s)
      onSaved()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  const fillPctValue = Math.round((s.maxFillLevel ?? 1) * 100)

  return (
    <div className="glass" style={{ padding: '1rem 1.25rem', margin: '0 0 1rem' }}>
      <h3 style={{ marginTop: 0 }}>{isNew ? 'Add shipper' : `Edit ${s.code}`}</h3>
      {error && <div className="alert-danger" style={{ marginBottom: '.75rem' }}>{error}</div>}
      <div className={styles.grid}>
        <div className={styles.field}>
          <label>Code</label>
          <input className="form-control" value={s.code} onChange={(e) => set({ code: e.target.value })} />
        </div>
        <div className={styles.field}>
          <label>Name</label>
          <input className="form-control" value={s.name ?? ''} onChange={(e) => set({ name: e.target.value })} />
        </div>
        <div className={styles.field}>
          <label>Type</label>
          <Select
            ariaLabel="Shipper type"
            value={s.shipperType ?? 'CARTON'}
            onChange={(v) => set({ shipperType: v })}
            options={['BOX', 'TOTE', 'BAG', 'CARTON', 'PALLET'].map((t) => ({ value: t, label: t }))}
          />
        </div>
      </div>
      <div className={styles.grid}>
        <NumberField label="Inner length (mm)" value={s.lengthMm ?? 0} step="1" onChange={(v) => set({ lengthMm: v })} />
        <NumberField label="Inner width (mm)" value={s.widthMm ?? 0} step="1" onChange={(v) => set({ widthMm: v })} />
        <NumberField label="Inner height (mm)" value={s.heightMm ?? 0} step="1" onChange={(v) => set({ heightMm: v })} />
      </div>
      <div className={styles.grid}>
        <NumberField
          label={<>Fill rate (%) <InfoTip text="Usable fraction of the inner volume cubing may fill (accounts for void/dunnage)." example="85" /></>}
          value={fillPctValue}
          step="1"
          onChange={(v) => set({ maxFillLevel: Math.max(0, Math.min(100, v)) / 100 })}
        />
        <NumberField label="Tare weight (g)" value={s.tareWeightG ?? 0} step="1" onChange={(v) => set({ tareWeightG: v })} />
        <NumberField label="Max gross weight (g)" value={s.maxWeightG ?? 0} step="1" onChange={(v) => set({ maxWeightG: v })} />
      </div>
      <div className={styles.field} style={{ maxWidth: 220 }}>
        <label>Status</label>
        <Select
          ariaLabel="Status"
          value={s.status ?? 'ACTIVE'}
          onChange={(v) => set({ status: v })}
          options={[
            { value: 'ACTIVE', label: 'ACTIVE' },
            { value: 'ARCHIVED', label: 'ARCHIVED' },
          ]}
        />
      </div>
      <div className={styles.actions}>
        <button className="btn btn-primary" onClick={save} disabled={saving}>
          {saving ? 'Saving…' : isNew ? 'Create shipper' : 'Save shipper'}
        </button>
        <button className="btn btn-ghost" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
      </div>
    </div>
  )
}

// Demo mode (ADMIN). Seeds a sample catalog onto an empty, host-free system and removes it again
// when switched off. The toggle is locked on unless the system is empty (no host data).
function DemoMode({ warehouseId }: { warehouseId: string }) {
  const [status, setStatus] = useState<DemoStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<DemoResult | null>(null)

  function refresh() {
    getDemoStatus(warehouseId)
      .then(setStatus)
      .catch((e) => setError(String(e)))
  }
  useEffect(refresh, [warehouseId])

  async function toggle(next: boolean) {
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      const res = next ? await enableDemo(warehouseId) : await disableDemo(warehouseId)
      setResult(res)
      refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const enabled = status?.enabled ?? false
  const blocked = !enabled && status != null && !status.canEnable // can't enable: not a fresh system

  return (
    <section className={`glass ${styles.section}`}>
      <div className={styles.sectionHead}>
        <div>
          <h2>Demo mode</h2>
          <p>
            Seed a sample catalog — plus handling units and stock — to explore openWCS without a host.
            Available only on a fresh system (no host data) that already has storage locations. Turning
            it off removes everything demo mode created.
          </p>
        </div>
      </div>

      {error && <div className="alert-danger" style={{ marginBottom: '1rem' }}>{error}</div>}

      <div className={styles.toggleRow} style={{ marginBottom: '1rem' }}>
        <Toggle checked={enabled} onChange={(v) => !busy && !(!enabled && blocked) && toggle(v)} />
        <div>
          <div>{busy ? 'Working…' : enabled ? 'Demo mode is ON' : 'Demo mode is OFF'} <InfoTip text="Seeds a sample catalog, handling units and stock so you can explore openWCS without a host. Only enables on a fresh system; switching off removes all demo and operational data for this warehouse." example="Off" /></div>
          <span className={styles.fieldHint}>
            {blocked
              ? (status?.skuCount ?? 0) > 0
                ? `Locked: the system already has ${status?.skuCount} SKUs (host data present). Demo mode only seeds an empty system.`
                : 'Locked: create storage locations for this warehouse first — demo mode places handling units and stock into existing locations.'
              : enabled
                ? 'Switch off for a full reset: deletes the whole SKU catalog and ALL operational data for this warehouse — stock, reservations, handling units, inbound/outbound orders, transports, counts, GTP work and the transaction journal. Warehouses, locations, topology, GTP/station config and equipment are kept.'
                : 'Switch on to create 100 demo SKUs (movie-merch named, with EAN-13 barcodes), shippers, a storage HU type, handling units with stock, and 50 empty handling units — all placed into this warehouse’s locations.'}
          </span>
        </div>
      </div>

      {result && (
        <div className={styles.fieldHint} style={{ marginTop: '.5rem' }}>
          {enabled ? 'Seeded' : 'Removed'}: {result.skus} SKUs · {result.unitsOfMeasure} units of measure ·{' '}
          {result.barcodes} barcodes · {result.shippers} shippers · {result.handlingUnitTypes} HU type
          {result.handlingUnits != null
            ? ` · ${result.handlingUnits} handling units${
                result.emptyHandlingUnits ? ` (${result.emptyHandlingUnits} empty)` : ''
              } · ${result.stockRows ?? 0} stock rows`
            : ''}
          .
        </div>
      )}
    </section>
  )
}

// Hardware emulator mode (ADMIN). A global switch: when ON the device adapters simulate all
// equipment and the system never connects to physical hardware; when OFF the adapters use the
// real connection path. Defaults OFF.
function EmulatorMode() {
  const [status, setStatus] = useState<EmulatorStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function refresh() {
    getEmulatorStatus()
      .then(setStatus)
      .catch((e) => setError(String(e)))
  }
  useEffect(refresh, [])

  async function toggle(next: boolean) {
    setBusy(true)
    setError(null)
    try {
      setStatus(next ? await enableEmulator() : await disableEmulator())
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const enabled = status?.enabled ?? false

  return (
    <section className={`glass ${styles.section}`}>
      <div className={styles.sectionHead}>
        <div>
          <h2>Hardware emulator</h2>
          <p>
            Run the whole automation flow against simulated equipment. When ON, the device adapters
            (conveyor, ASRS, AMR, AutoStore) emulate their commands and the system never connects to
            physical hardware. Turn it OFF once real adapters are configured. Global, not per warehouse.
          </p>
        </div>
      </div>

      {error && <div className="alert-danger" style={{ marginBottom: '1rem' }}>{error}</div>}

      <div className={styles.toggleRow} style={{ marginBottom: '1rem' }}>
        <Toggle checked={enabled} onChange={(v) => !busy && toggle(v)} />
        <div>
          <div>
            {busy ? 'Working…' : enabled ? 'Emulator is ON' : 'Emulator is OFF'}{' '}
            <InfoTip
              text="When ON, device adapters simulate all equipment and never open a connection to physical hardware. When OFF, the adapters use the real hardware connection path."
              example="Off (connects to real hardware when configured)"
            />
          </div>
          <span className={styles.fieldHint}>
            {enabled
              ? 'Simulated hardware: every device command is emulated and no physical connection is opened. Ideal for evaluation, onboarding and CI.'
              : 'Live hardware: adapters use the real connection path. Switch ON to run with no physical equipment.'}
          </span>
        </div>
      </div>
    </section>
  )
}
