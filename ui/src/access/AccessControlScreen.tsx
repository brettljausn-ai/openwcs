import { useEffect, useMemo, useState } from 'react'
import UserAutocomplete from '../ui/UserAutocomplete'

// Roles the matrix offers as columns. Mirrors the Role union in ui/src/auth/screens.ts
// (not imported — this screen keeps a local copy of the catalog per the access-control brief).
const ROLES = ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER'] as const
type Role = (typeof ROLES)[number]

// Local mirror of the screen catalog (key + label + section) from ui/src/auth/screens.ts.
// Kept in sync by hand: agents adding a screen there should add it here too.
interface CatalogEntry {
  key: string
  label: string
  section: string
  // The screen's built-in default roles (mirror of defaultRoles in ui/src/auth/screens.ts).
  // When a screen has no override, the toggles reflect these so an admin sees who can
  // already open it before deciding to override.
  defaultRoles: Role[]
}
const CATALOG: CatalogEntry[] = [
  { key: 'dashboard', label: 'Dashboard', section: 'General', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER'] },
  { key: 'inbound', label: 'Inbound orders', section: 'Operations', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'] },
  { key: 'outbound', label: 'Outbound orders', section: 'Operations', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'] },
  { key: 'counting', label: 'Stock counting', section: 'Operations', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'] },
  { key: 'gtp-ops', label: 'GTP workplaces', section: 'Operations', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'] },
  { key: 'transport', label: 'Transport', section: 'Operations', defaultRoles: ['ADMIN', 'SUPERVISOR'] },
  { key: 'stock-transactions', label: 'Stock transactions', section: 'Operations', defaultRoles: ['ADMIN', 'SUPERVISOR'] },
  { key: 'topology', label: 'Conveyor topology', section: 'Engineering', defaultRoles: ['ADMIN', 'SUPERVISOR'] },
  { key: 'processes', label: 'Processes', section: 'Engineering', defaultRoles: ['ADMIN', 'SUPERVISOR'] },
  { key: 'slotting', label: 'Slotting', section: 'Engineering', defaultRoles: ['ADMIN', 'SUPERVISOR'] },
  { key: 'master-data', label: 'Master data', section: 'Configuration', defaultRoles: ['ADMIN', 'SUPERVISOR'] },
  { key: 'gtp-config', label: 'GTP workplaces', section: 'Configuration', defaultRoles: ['ADMIN'] },
  { key: 'settings', label: 'Settings', section: 'Configuration', defaultRoles: ['ADMIN'] },
  { key: 'users', label: 'User management', section: 'Administration', defaultRoles: ['ADMIN'] },
  { key: 'access-control', label: 'Access control', section: 'Administration', defaultRoles: ['ADMIN'] },
  { key: 'warehouse-access', label: 'Warehouse access', section: 'Administration', defaultRoles: ['ADMIN'] },
]
const CATALOG_BY_KEY: Record<string, CatalogEntry> = Object.fromEntries(CATALOG.map((e) => [e.key, e]))

// The backend/AuthContext shape: { "<screenKey>": { roles?: string[], users?: string[] } }.
type Override = { roles?: string[]; users?: string[] }
type AccessMap = Record<string, Override>

// Per-screen editable state. `overridden` distinguishes an explicit override from a row
// that's merely *showing* the built-in defaults (so toggling the latter reflects reality
// without counting as an override until the admin actually changes something).
interface RowState {
  overridden: boolean
  roles: Record<Role, boolean>
  users: string[]
}

function rolesRecord(active: readonly string[]): Record<Role, boolean> {
  return {
    ADMIN: active.includes('ADMIN'),
    SUPERVISOR: active.includes('SUPERVISOR'),
    OPERATOR: active.includes('OPERATOR'),
    VIEWER: active.includes('VIEWER'),
  }
}

// A non-overridden row that mirrors the screen's built-in default roles.
function defaultRow(entry: CatalogEntry): RowState {
  return { overridden: false, roles: rolesRecord(entry.defaultRoles), users: [] }
}

function rowFromOverride(entry: CatalogEntry, o: Override | undefined): RowState {
  const hasOverride = !!(o && ((o.roles?.length ?? 0) > 0 || (o.users?.length ?? 0) > 0))
  if (!hasOverride) return defaultRow(entry)
  return {
    overridden: true,
    roles: rolesRecord((o?.roles ?? []).filter((r) => (ROLES as readonly string[]).includes(r))),
    users: o?.users ?? [],
  }
}

export default function AccessControlScreen() {
  const [rows, setRows] = useState<Record<string, RowState>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [savedAt, setSavedAt] = useState<number | null>(null)

  function applyMap(map: AccessMap) {
    const next: Record<string, RowState> = {}
    for (const entry of CATALOG) next[entry.key] = rowFromOverride(entry, map[entry.key])
    setRows(next)
  }

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    fetch('/api/iam/screen-access')
      .then((r) => {
        if (!r.ok) throw new Error(`Failed to load access map (HTTP ${r.status})`)
        return r.json()
      })
      .then((data: AccessMap) => {
        if (!cancelled) {
          applyMap(data && typeof data === 'object' ? data : {})
          setError(null)
        }
      })
      .catch((e) => {
        if (!cancelled) {
          applyMap({})
          setError(e instanceof Error ? e.message : 'Failed to load access map.')
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const overriddenCount = useMemo(
    () => Object.values(rows).filter((r) => r.overridden).length,
    [rows],
  )

  // Changing any toggle (or the user list) on a default row "takes control" of it: the
  // reflected defaults become the starting point of an explicit override.
  function toggleRole(key: string, role: Role) {
    setSavedAt(null)
    setRows((prev) => {
      const row = prev[key]
      return { ...prev, [key]: { ...row, overridden: true, roles: { ...row.roles, [role]: !row.roles[role] } } }
    })
  }

  function setUsers(key: string, usernames: string[]) {
    setSavedAt(null)
    setRows((prev) => {
      const row = prev[key]
      // Keep it a default row only while the list is being cleared back to empty.
      const overridden = row.overridden || usernames.length > 0
      return { ...prev, [key]: { ...row, overridden, users: usernames } }
    })
  }

  function clearRow(key: string) {
    setSavedAt(null)
    setRows((prev) => ({ ...prev, [key]: defaultRow(CATALOG_BY_KEY[key]) }))
  }

  function buildMap(): AccessMap {
    const map: AccessMap = {}
    for (const entry of CATALOG) {
      const row = rows[entry.key]
      if (!row || !row.overridden) continue // default row → UI defaults apply
      const roles = ROLES.filter((r) => row.roles[r])
      const users = row.users
      if (roles.length === 0 && users.length === 0) continue // emptied override → back to defaults
      map[entry.key] = { roles, users }
    }
    return map
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      const res = await fetch('/api/iam/screen-access', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildMap()),
      })
      if (!res.ok) throw new Error(`Save failed (HTTP ${res.status})`)
      const data: AccessMap = await res.json()
      applyMap(data && typeof data === 'object' ? data : {})
      setSavedAt(Date.now())
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  const sections = useMemo(() => {
    const order: string[] = []
    for (const entry of CATALOG) if (!order.includes(entry.section)) order.push(entry.section)
    return order
  }, [])

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">Administration</div>
        <h1>Access control</h1>
        <p>
          Map each screen to the roles and individual users that may open it. A screen with no
          selection here falls back to its built-in default roles. ADMIN always has access.
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
            {loading ? 'Loading…' : `${overriddenCount} of ${CATALOG.length} screens overridden`}
          </div>
          <div style={{ display: 'flex', gap: '.6rem', alignItems: 'center' }}>
            {savedAt && <span className="badge badge-success">Saved</span>}
            <button className="btn btn-primary" onClick={save} disabled={loading || saving}>
              {saving ? 'Saving…' : 'Save changes'}
            </button>
          </div>
        </div>

        <table>
          <thead>
            <tr>
              <th style={{ minWidth: 180 }}>Screen</th>
              {ROLES.map((r) => (
                <th key={r} style={{ textAlign: 'center' }}>
                  {r}
                </th>
              ))}
              <th style={{ minWidth: 220 }}>Allowed users</th>
              <th style={{ width: 1 }}></th>
            </tr>
          </thead>
          <tbody>
            {sections.map((section) => (
              <SectionRows
                key={section}
                section={section}
                rows={rows}
                loading={loading}
                onToggleRole={toggleRole}
                onSetUsers={setUsers}
                onClear={clearRow}
              />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function SectionRows({
  section,
  rows,
  loading,
  onToggleRole,
  onSetUsers,
  onClear,
}: {
  section: string
  rows: Record<string, RowState>
  loading: boolean
  onToggleRole: (key: string, role: Role) => void
  onSetUsers: (key: string, usernames: string[]) => void
  onClear: (key: string) => void
}) {
  const entries = CATALOG.filter((e) => e.section === section)
  return (
    <>
      <tr>
        <td colSpan={ROLES.length + 3} className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.7rem', letterSpacing: '.12em', textTransform: 'uppercase' }}>
          {section}
        </td>
      </tr>
      {entries.map((entry) => {
        const row = rows[entry.key] ?? defaultRow(entry)
        const overridden = row.overridden
        return (
          <tr key={entry.key}>
            <td>
              <div>{entry.label}</div>
              <div className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.7rem' }}>
                {entry.key}
                {!overridden && (
                  <span className="badge badge-info" style={{ marginLeft: '.5rem' }}>
                    default
                  </span>
                )}
              </div>
            </td>
            {ROLES.map((r) => (
              <td key={r} style={{ textAlign: 'center' }}>
                <Toggle
                  label={`${entry.label}: ${r}`}
                  checked={row.roles[r]}
                  muted={!overridden}
                  disabled={loading}
                  onChange={() => onToggleRole(entry.key, r)}
                />
              </td>
            ))}
            <td>
              <UserAutocomplete
                value={row.users}
                onChange={(u) => onSetUsers(entry.key, u)}
                ariaLabel={`Allowed users for ${entry.label}`}
              />
            </td>
            <td>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => onClear(entry.key)}
                disabled={loading || !overridden}
                title="Clear override (revert to defaults)"
              >
                Clear
              </button>
            </td>
          </tr>
        )
      })}
    </>
  )
}

// Toggle switch. `muted` renders the dimmed "this is just reflecting the default" state.
function Toggle({
  label,
  checked,
  muted,
  disabled,
  onChange,
}: {
  label: string
  checked: boolean
  muted?: boolean
  disabled?: boolean
  onChange: () => void
}) {
  return (
    <label className={`switch${muted ? ' switch-muted' : ''}`}>
      <input type="checkbox" aria-label={label} checked={checked} disabled={disabled} onChange={onChange} />
      <span className="switch-track">
        <span className="switch-thumb" />
      </span>
    </label>
  )
}
