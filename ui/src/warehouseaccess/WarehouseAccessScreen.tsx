import { useEffect, useMemo, useRef, useState } from 'react'
import Select from '../ui/Select'
import { countUsers, searchUsers, KcUser } from '../users/api'
import { getAllAccess, listWarehouses, setAccess, Warehouse } from './api'

// Per-user warehouse access. Users are searched + paginated SERVER-SIDE (Keycloak) so the screen
// scales to thousands of users; each warehouse is a toggle (may they work there?) and a Default
// selector picks one of the user's allowed warehouses (auto-selected when they sign in). Edits are
// held in an overlay keyed by username so they survive paging, and saved together. Admins are
// never warehouse-scoped, so they don't need a mapping here.
interface RowState {
  allowed: string[]
  default: string | null
}

const PAGE_SIZE = 20

function rowFromAccess(a: { warehouses: string[]; defaultWarehouse: string | null } | undefined): RowState {
  return { allowed: a?.warehouses ?? [], default: a?.defaultWarehouse ?? null }
}

function sameRow(a: RowState, b: RowState): boolean {
  if ((a.default ?? '') !== (b.default ?? '')) return false
  if (a.allowed.length !== b.allowed.length) return false
  const set = new Set(a.allowed)
  return b.allowed.every((id) => set.has(id))
}

export default function WarehouseAccessScreen() {
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [access, setAccessMap] = useState<Record<string, { warehouses: string[]; defaultWarehouse: string | null }>>({})
  const [edits, setEdits] = useState<Record<string, RowState>>({})

  const [query, setQuery] = useState('')
  const [first, setFirst] = useState(0)
  const [users, setUsers] = useState<KcUser[]>([])
  const [total, setTotal] = useState(0)

  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  // Warehouses + the (small) map of users-with-mappings load once.
  useEffect(() => {
    let cancelled = false
    Promise.all([listWarehouses(), getAllAccess()])
      .then(([whs, map]) => {
        if (cancelled) return
        setWarehouses(whs)
        setAccessMap(map)
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to load warehouse access.'))
    return () => {
      cancelled = true
    }
  }, [])

  // Server-side user search (debounced) + count, re-run on query/page change.
  const debounce = useRef<ReturnType<typeof setTimeout>>()
  useEffect(() => {
    setLoading(true)
    clearTimeout(debounce.current)
    debounce.current = setTimeout(() => {
      let cancelled = false
      Promise.all([searchUsers({ search: query, first, max: PAGE_SIZE }), countUsers(query)])
        .then(([list, count]) => {
          if (cancelled) return
          setUsers(list)
          setTotal(count)
          setError(null)
        })
        .catch((e) => !cancelled && setError(e instanceof Error ? e.message : 'Failed to search users.'))
        .finally(() => !cancelled && setLoading(false))
      return () => {
        cancelled = true
      }
    }, 250)
    return () => clearTimeout(debounce.current)
  }, [query, first])

  function effective(username: string): RowState {
    return edits[username] ?? rowFromAccess(access[username])
  }

  const dirty = useMemo(
    () => Object.keys(edits).filter((u) => !sameRow(edits[u], rowFromAccess(access[u]))),
    [edits, access],
  )

  function update(username: string, next: RowState) {
    setSavedAt(null)
    setEdits((prev) => ({ ...prev, [username]: next }))
  }

  function toggleWarehouse(username: string, whId: string) {
    const row = effective(username)
    const has = row.allowed.includes(whId)
    const allowed = has ? row.allowed.filter((x) => x !== whId) : [...row.allowed, whId]
    const def = has && row.default === whId ? null : row.default // removing the default clears it
    update(username, { allowed, default: def })
  }

  function setDefault(username: string, whId: string) {
    update(username, { ...effective(username), default: whId || null })
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await Promise.all(dirty.map((u) => setAccess(u, { warehouses: edits[u].allowed, defaultWarehouse: edits[u].default })))
      // Fold the saved edits into the base map and clear the overlay.
      setAccessMap((prev) => {
        const nextMap = { ...prev }
        for (const u of dirty) nextMap[u] = { warehouses: edits[u].allowed, defaultWarehouse: edits[u].default }
        return nextMap
      })
      setEdits({})
      setSavedAt(Date.now())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const currentPage = Math.floor(first / PAGE_SIZE) + 1

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
            gap: '.75rem',
            marginBottom: '1rem',
            flexWrap: 'wrap',
          }}
        >
          <input
            className="form-control"
            type="search"
            style={{ maxWidth: 320 }}
            placeholder="Search users…"
            value={query}
            onChange={(e) => {
              setFirst(0)
              setQuery(e.target.value)
            }}
          />
          <span className="muted" style={{ fontSize: '.82rem', whiteSpace: 'nowrap' }}>
            {total} users · {warehouses.length} warehouses · {dirty.length} unsaved
          </span>
          <div style={{ flex: 1 }} />
          {savedAt && <span className="badge badge-success">Saved</span>}
          <button className="btn btn-primary" onClick={save} disabled={loading || saving || dirty.length === 0}>
            {saving ? 'Saving…' : 'Save changes'}
          </button>
        </div>

        {warehouses.length === 0 ? (
          <div className="muted">No warehouses defined yet — create one under Master data first.</div>
        ) : (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table>
                <thead>
                  <tr>
                    <th style={{ minWidth: 200 }}>User</th>
                    {warehouses.map((w) => (
                      <th key={w.id} style={{ textAlign: 'center' }} title={w.name}>
                        {w.code}
                      </th>
                    ))}
                    <th style={{ minWidth: 220 }}>Default</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr>
                      <td colSpan={warehouses.length + 2} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                        Loading…
                      </td>
                    </tr>
                  ) : users.length === 0 ? (
                    <tr>
                      <td colSpan={warehouses.length + 2} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                        No users match “{query}”.
                      </td>
                    </tr>
                  ) : (
                    users.map((u) => {
                      const row = effective(u.username)
                      const allowedWarehouses = warehouses.filter((w) => row.allowed.includes(w.id))
                      const isDirty = !!edits[u.username] && !sameRow(edits[u.username], rowFromAccess(access[u.username]))
                      return (
                        <tr key={u.id}>
                          <td>
                            <div>
                              {u.username}
                              {isDirty && <span className="badge badge-warning" style={{ marginLeft: '.5rem' }}>edited</span>}
                            </div>
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
                                onChange={() => toggleWarehouse(u.username, w.id)}
                              />
                            </td>
                          ))}
                          <td>
                            <Select
                              ariaLabel={`Default warehouse for ${u.username}`}
                              value={row.default ?? ''}
                              onChange={(v) => setDefault(u.username, v)}
                              placeholder="— none —"
                              options={[
                                { value: '', label: '— none —' },
                                ...allowedWarehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` })),
                              ]}
                            />
                          </td>
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>

            {pageCount > 1 && (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '1rem', marginTop: '.75rem' }}>
                <button
                  className="btn btn-ghost btn-sm"
                  disabled={first === 0 || loading}
                  onClick={() => setFirst(Math.max(0, first - PAGE_SIZE))}
                >
                  Prev
                </button>
                <span className="muted">Page {currentPage} of {pageCount}</span>
                <button
                  className="btn btn-ghost btn-sm"
                  disabled={currentPage >= pageCount || loading}
                  onClick={() => setFirst(first + PAGE_SIZE)}
                >
                  Next
                </button>
              </div>
            )}
          </>
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
