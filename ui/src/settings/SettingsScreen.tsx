import { useEffect, useState } from 'react'
import { useAuth } from '../auth/AuthContext'
import Select from '../ui/Select'
import styles from './settings.module.css'
import {
  AdapterInfo,
  BlockPolicy,
  CountSchedule,
  DemoResult,
  DemoStatus,
  HealthStatus,
  StorageBlock,
  Warehouse,
  createSchedule,
  defaultBlockPolicy,
  disableDemo,
  enableDemo,
  generateDueTasks,
  getBlockPolicy,
  getDemoStatus,
  getGatewayHealth,
  listAdapters,
  listSchedules,
  listStorageBlocks,
  listWarehouses,
  saveBlockPolicy,
} from './api'

type Tab = 'slotting' | 'counting' | 'integration' | 'system' | 'demo'

const TABS: { key: Tab; label: string }[] = [
  { key: 'slotting', label: 'Slotting policy' },
  { key: 'counting', label: 'Counting' },
  { key: 'integration', label: 'Integrations' },
  { key: 'system', label: 'System status' },
  { key: 'demo', label: 'Demo mode' },
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

  const needsWarehouse = tab === 'slotting' || tab === 'counting' || tab === 'demo'

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
                <label htmlFor="wh">Warehouse</label>
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
          {tab === 'counting' && <CountingSettings warehouseId={warehouseId} />}
          {tab === 'integration' && <Integrations />}
          {tab === 'system' && <SystemStatus />}
          {tab === 'demo' && <DemoMode warehouseId={warehouseId} />}
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
  label: string
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
        <label htmlFor="block">Storage block</label>
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
            <NumberField label="Velocity" value={policy.wVelocity} onChange={(v) => patch({ wVelocity: v })} hint="Velocity-to-exit weight" />
            <NumberField label="Consolidation" value={policy.wConsolidation} onChange={(v) => patch({ wConsolidation: v })} hint="Same-SKU consolidation" />
            <NumberField label="Redundancy" value={policy.wRedundancy} onChange={(v) => patch({ wRedundancy: v })} hint="Aisle redundancy" />
            <NumberField label="Balance" value={policy.wBalance} onChange={(v) => patch({ wBalance: v })} hint="Fill balance" />
          </div>

          <h3 style={{ margin: '1.25rem 0 .5rem', fontSize: '.95rem' }}>Aisle constraints</h3>
          <div className={styles.grid}>
            <NumberField label="Max aisle %" value={policy.defaultMaxAislePct} step="0.05" onChange={(v) => patch({ defaultMaxAislePct: v })} hint="Cap of one SKU per aisle (0–1)" />
            <NumberField label="Min aisles · A" value={policy.minAislesA} step="1" onChange={(v) => patch({ minAislesA: v })} />
            <NumberField label="Min aisles · B" value={policy.minAislesB} step="1" onChange={(v) => patch({ minAislesB: v })} />
            <NumberField label="Min aisles · C" value={policy.minAislesC} step="1" onChange={(v) => patch({ minAislesC: v })} />
          </div>

          <h3 style={{ margin: '1.25rem 0 .5rem', fontSize: '.95rem' }}>Re-slotting</h3>
          <div className={styles.toggleRow} style={{ marginBottom: '1rem' }}>
            <Toggle checked={policy.reslotEnabled} onChange={(v) => patch({ reslotEnabled: v })} />
            <div>
              <div>Automatic re-slotting</div>
              <span className={styles.fieldHint}>Move stock toward optimal slots during off-peak windows.</span>
            </div>
          </div>
          <div className={styles.grid}>
            <NumberField
              label="Re-slot shift %"
              value={policy.reslotShiftPct}
              step="0.05"
              onChange={(v) => patch({ reslotShiftPct: v })}
              hint="Max share of stock moved per run (0–1)"
            />
            <div className={styles.field}>
              <label>Off-peak cron</label>
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
          <label>Name</label>
          <input className="form-control" value={form.name} placeholder="A-class weekly" onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </div>
        <div className={styles.field}>
          <label>Scope</label>
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
            <label>ABC class</label>
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
          <label>Count type</label>
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
        <NumberField label="Cadence (days)" value={form.cadenceDays} step="1" onChange={(v) => setForm({ ...form, cadenceDays: v })} />
        <NumberField label="Tolerance" value={form.tolerance} step="0.01" onChange={(v) => setForm({ ...form, tolerance: v })} hint="Accepted variance" />
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
          <div>{busy ? 'Working…' : enabled ? 'Demo mode is ON' : 'Demo mode is OFF'}</div>
          <span className={styles.fieldHint}>
            {blocked
              ? (status?.skuCount ?? 0) > 0
                ? `Locked: the system already has ${status?.skuCount} SKUs (host data present). Demo mode only seeds an empty system.`
                : 'Locked: create storage locations for this warehouse first — demo mode places handling units and stock into existing locations.'
              : enabled
                ? 'Switch off for a full reset: deletes the demo catalog and ALL operational data for this warehouse — stock, reservations, handling units, inbound/outbound orders, transports, counts and GTP work. Warehouses, locations, topology, GTP/station config and equipment are kept.'
                : 'Switch on to create 100 demo SKUs (movie-merch named, with EAN-13 barcodes), shippers, a storage HU type, and handling units with stock placed into this warehouse’s locations.'}
          </span>
        </div>
      </div>

      {result && (
        <div className={styles.fieldHint} style={{ marginTop: '.5rem' }}>
          {enabled ? 'Seeded' : 'Removed'}: {result.skus} SKUs · {result.unitsOfMeasure} units of measure ·{' '}
          {result.barcodes} barcodes · {result.shippers} shippers · {result.handlingUnitTypes} HU type
          {result.handlingUnits != null
            ? ` · ${result.handlingUnits} handling units · ${result.stockRows ?? 0} stock rows`
            : ''}
          .
        </div>
      )}
    </section>
  )
}
