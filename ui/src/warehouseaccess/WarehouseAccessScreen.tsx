import { useEffect, useMemo, useState } from 'react'
import { listUsers, KcUser } from '../users/api'
import { getAllAccess, listWarehouses, setAccess, Warehouse } from './api'

// Per-user warehouse access. Rows are users; each warehouse is a toggle (may they work there?),
// and a Default selector picks one of that user's allowed warehouses (auto-selected on their login).
// Admins are never warehouse-scoped, so they don't need a mapping here.
interface RowState {
  allowed: string[]
  default: string | null
}

function sameRow(a: RowState, b: RowState): boolean {
  if ((a.default ?? '') !== (b.default ?? '')) return false
  if (a.allowed.length !== b.allowed.length) return false
  const set = new Set(a.allowed)
  return b.allowed.every((id) => set.has(id))
}

export default function WarehouseAccessScreen() {
  const [users, setUsers] = useState<KcUser[]>([])
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [rows, setRows] = useState<Record<string, RowState>>({})
  const [original, setOriginal] = useState<Record<string, RowState>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  async function reload() {
    setLoading(true)
    setError(null)
    try {
      const [userList, whList, accessMap] = await Promise.all([listUsers(), listWarehouses(), getAllAccess()])
      const next: Record<string, RowState> = {}
      for (const u of userList) {
        const a = accessMap[u.username]
        next[u.username] = { allowed: a?.warehouses ?? [], default: a?.defaultWarehouse ?? null }
      }
      setUsers(userList)
      setWarehouses(whList)
      setRows(next)
      setOriginal(structuredClone(next))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to load warehouse access.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  function toggleWarehouse(username: string, whId: string) {
    setSavedAt(null)
    setRows((prev) => {
      const row = prev[username]
      const has = row.allowed.includes(whId)
      const allowed = has ? row.allowed.filter((x) => x !== whId) : [...row.allowed, whId]
      // Removing the warehouse that was the default clears the default.
      const def = has && row.default === whId ? null : row.default
      return { ...prev, [username]: { allowed, default: def } }
    })
  }

  function setDefault(username: string, whId: string) {
    setSavedAt(null)
    setRows((prev) => ({ ...prev, [username]: { ...prev[username], default: whId || null } }))
  }

  const dirty = useMemo(
    () => Object.keys(rows).filter((u) => original[u] && !sameRow(rows[u], original[u])),
    [rows, original],
  )

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await Promise.all(
        dirty.map((u) => setAccess(u, { warehouses: rows[u].allowed, defaultWarehouse: rows[u].default })),
      )
      setOriginal(structuredClone(rows))
      setSavedAt(Date.now())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">Administration</div>
        <h1>Warehouse access</h1>
        <p>
          Choose which warehouses each user may work in and their default (selected automatically when
          they sign in). Users switch among their allowed warehouses from the top bar; only admins change
          this mapping. Admins are never warehouse-scoped.
        </p>
      </div>

      {error && (
        <div className="alert-danger" role="alert" style={{ marginBottom: '1rem' }}>
          {error}
        </div>
      )}

      <div className="glass card-pad">
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '1rem',
            marginBottom: '1rem',
            flexWrap: 'wrap',
          }}
        >
          <div className="muted">
            {loading
              ? 'Loading…'
              : `${users.length} users · ${warehouses.length} warehouses · ${dirty.length} unsaved`}
          </div>
          <div style={{ display: 'flex', gap: '.6rem', alignItems: 'center' }}>
            {savedAt && <span className="badge badge-success">Saved</span>}
            <button className="btn btn-primary" onClick={save} disabled={loading || saving || dirty.length === 0}>
              {saving ? 'Saving…' : 'Save changes'}
            </button>
          </div>
        </div>

        {!loading && warehouses.length === 0 && (
          <div className="muted">No warehouses defined yet — create one under Master data first.</div>
        )}

        {!loading && warehouses.length > 0 && (
          <table>
            <thead>
              <tr>
                <th style={{ minWidth: 200 }}>User</th>
                {warehouses.map((w) => (
                  <th key={w.id} style={{ textAlign: 'center' }} title={w.name}>
                    {w.code}
                  </th>
                ))}
                <th style={{ minWidth: 200 }}>Default</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => {
                const row = rows[u.username] ?? { allowed: [], default: null }
                const allowedWarehouses = warehouses.filter((w) => row.allowed.includes(w.id))
                return (
                  <tr key={u.id}>
                    <td>
                      <div>{u.username}</div>
                      {(u.firstName || u.lastName) && (
                        <div className="muted" style={{ fontSize: '.78rem' }}>
                          {[u.firstName, u.lastName].filter(Boolean).join(' ')}
                        </div>
                      )}
                    </td>
                    {warehouses.map((w) => (
                      <td key={w.id} style={{ textAlign: 'center' }}>
                        <Toggle
                          label={`${u.username}: ${w.code}`}
                          checked={row.allowed.includes(w.id)}
                          disabled={loading}
                          onChange={() => toggleWarehouse(u.username, w.id)}
                        />
                      </td>
                    ))}
                    <td>
                      <select
                        className="form-control"
                        value={row.default ?? ''}
                        disabled={loading || allowedWarehouses.length === 0}
                        onChange={(e) => setDefault(u.username, e.target.value)}
                      >
                        <option value="">— none —</option>
                        {allowedWarehouses.map((w) => (
                          <option key={w.id} value={w.id}>
                            {w.code} — {w.name}
                          </option>
                        ))}
                      </select>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

// Toggle switch (shares the .switch styles in theme/app.css).
function Toggle({
  label,
  checked,
  disabled,
  onChange,
}: {
  label: string
  checked: boolean
  disabled?: boolean
  onChange: () => void
}) {
  return (
    <label className="switch">
      <input type="checkbox" aria-label={label} checked={checked} disabled={disabled} onChange={onChange} />
      <span className="switch-track">
        <span className="switch-thumb" />
      </span>
    </label>
  )
}
