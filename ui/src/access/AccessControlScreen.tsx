import { useEffect, useMemo, useState } from 'react'
import { useT } from '../i18n/useT'
import UserAutocomplete from '../ui/UserAutocomplete'
import { AccessLevel, Role, SCREENS, defaultLevel } from '../auth/screens'

// Roles the matrix offers as columns. Mirrors the Role union in ui/src/auth/screens.ts.
const ROLES: Role[] = ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER']

// The catalog is derived from the canonical screen list (ui/src/auth/screens.ts) so it can never
// drift: every screen appears here automatically with its built-in default level per role.
// Dashboard has no section (top-level) → shown under "General".
interface CatalogEntry {
  key: string
  label: string
  section: string
  defaults: Record<Role, AccessLevel | null>
}
const CATALOG: CatalogEntry[] = SCREENS.map((s) => ({
  key: s.key,
  label: s.label,
  section: s.section ?? 'General',
  defaults: {
    ADMIN: defaultLevel(s, 'ADMIN'),
    SUPERVISOR: defaultLevel(s, 'SUPERVISOR'),
    OPERATOR: defaultLevel(s, 'OPERATOR'),
    VIEWER: defaultLevel(s, 'VIEWER'),
  },
}))
const CATALOG_BY_KEY: Record<string, CatalogEntry> = Object.fromEntries(CATALOG.map((e) => [e.key, e]))

// The backend/AuthContext shape: { "<screenKey>": { roles?: {role: level}, users?: {user: level} } }.
// A role/user absent from the maps is OFF for that overridden screen.
type Override = { roles?: Record<string, AccessLevel>; users?: Record<string, AccessLevel> }
type AccessMap = Record<string, Override>

// Per-screen editable state. `overridden` distinguishes an explicit override from a row that's
// merely *showing* the built-in defaults (so changing the latter reflects reality without counting
// as an override until the admin actually changes something). `null` role level = OFF.
interface RowState {
  overridden: boolean
  roles: Record<Role, AccessLevel | null>
  users: Record<string, AccessLevel>
}

// A non-overridden row that mirrors the screen's built-in default levels.
function defaultRow(entry: CatalogEntry): RowState {
  return { overridden: false, roles: { ...entry.defaults }, users: {} }
}

function rowFromOverride(entry: CatalogEntry, o: Override | undefined): RowState {
  const roleCount = o?.roles ? Object.keys(o.roles).length : 0
  const userCount = o?.users ? Object.keys(o.users).length : 0
  if (roleCount === 0 && userCount === 0) return defaultRow(entry)
  return {
    overridden: true,
    roles: {
      ADMIN: o?.roles?.ADMIN ?? null,
      SUPERVISOR: o?.roles?.SUPERVISOR ?? null,
      OPERATOR: o?.roles?.OPERATOR ?? null,
      VIEWER: o?.roles?.VIEWER ?? null,
    },
    users: { ...(o?.users ?? {}) },
  }
}

export default function AccessControlScreen() {
  const t = useT('access')
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

  // Changing any level (or the user list) on a default row "takes control" of it: the reflected
  // defaults become the starting point of an explicit override.
  function setRoleLevel(key: string, role: Role, level: AccessLevel | null) {
    setSavedAt(null)
    setRows((prev) => {
      const row = prev[key]
      return { ...prev, [key]: { ...row, overridden: true, roles: { ...row.roles, [role]: level } } }
    })
  }

  // Reconcile the username set from the autocomplete: new users default to WRITE, removed users drop.
  function setUsernames(key: string, usernames: string[]) {
    setSavedAt(null)
    setRows((prev) => {
      const row = prev[key]
      const next: Record<string, AccessLevel> = {}
      for (const u of usernames) next[u] = row.users[u] ?? 'write'
      const overridden = row.overridden || usernames.length > 0
      return { ...prev, [key]: { ...row, overridden, users: next } }
    })
  }

  function setUserLevel(key: string, username: string, level: AccessLevel) {
    setSavedAt(null)
    setRows((prev) => {
      const row = prev[key]
      return { ...prev, [key]: { ...row, overridden: true, users: { ...row.users, [username]: level } } }
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
      const roles: Record<string, AccessLevel> = {}
      for (const r of ROLES) if (row.roles[r]) roles[r] = row.roles[r] as AccessLevel
      const users = row.users
      if (Object.keys(roles).length === 0 && Object.keys(users).length === 0) continue // emptied → back to defaults
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
        <div className="eyebrow">{t('eyebrow', 'Administration')}</div>
        <h1>{t('title', 'Access control')}</h1>
        <p>
          {t('subtitle', 'Map each screen to the access each role and individual user has: off, read (view-only) or write (full). A screen with no selection here falls back to its built-in defaults. ADMIN always has full access.')}
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
              ? t('loading', 'Loading…')
              : t('overriddenCount', '{n} of {total} screens overridden')
                  .replace('{n}', String(overriddenCount))
                  .replace('{total}', String(CATALOG.length))}
          </div>
          <div style={{ display: 'flex', gap: '.6rem', alignItems: 'center' }}>
            {savedAt && <span className="badge badge-success">{t('saved', 'Saved')}</span>}
            <button className="btn btn-primary" onClick={save} disabled={loading || saving}>
              {saving ? t('saving', 'Saving…') : t('saveChanges', 'Save changes')}
            </button>
          </div>
        </div>

        <table>
          <thead>
            <tr>
              <th style={{ minWidth: 180 }}>{t('colScreen', 'Screen')}</th>
              {ROLES.map((r) => (
                <th key={r} style={{ textAlign: 'center' }}>
                  {r}
                </th>
              ))}
              <th style={{ minWidth: 260 }}>{t('colAllowedUsers', 'Allowed users')}</th>
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
                onSetRoleLevel={setRoleLevel}
                onSetUsernames={setUsernames}
                onSetUserLevel={setUserLevel}
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
  onSetRoleLevel,
  onSetUsernames,
  onSetUserLevel,
  onClear,
}: {
  section: string
  rows: Record<string, RowState>
  loading: boolean
  onSetRoleLevel: (key: string, role: Role, level: AccessLevel | null) => void
  onSetUsernames: (key: string, usernames: string[]) => void
  onSetUserLevel: (key: string, username: string, level: AccessLevel) => void
  onClear: (key: string) => void
}) {
  const t = useT('access')
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
        const usernames = Object.keys(row.users)
        return (
          <tr key={entry.key}>
            <td>
              <div>{entry.label}</div>
              <div className="muted" style={{ fontFamily: 'var(--font-mono)', fontSize: '.7rem' }}>
                {entry.key}
                {!overridden && (
                  <span className="badge badge-info" style={{ marginLeft: '.5rem' }}>
                    {t('default', 'default')}
                  </span>
                )}
              </div>
            </td>
            {ROLES.map((r) => (
              <td key={r} style={{ textAlign: 'center' }}>
                <LevelControl
                  label={`${entry.label}: ${r}`}
                  value={row.roles[r]}
                  options={['off', 'read', 'write']}
                  muted={!overridden}
                  disabled={loading}
                  onChange={(lvl) => onSetRoleLevel(entry.key, r, lvl)}
                  t={t}
                />
              </td>
            ))}
            <td>
              <UserAutocomplete
                value={usernames}
                onChange={(u) => onSetUsernames(entry.key, u)}
                ariaLabel={t('allowedUsersFor', 'Allowed users for {screen}').replace('{screen}', entry.label)}
              />
              {usernames.length > 0 && (
                <div className="user-levels">
                  {usernames.map((u) => (
                    <div key={u} className="user-level-row">
                      <span className="user-level-name">{u}</span>
                      <LevelControl
                        label={`${entry.label}: ${u}`}
                        value={row.users[u]}
                        options={['read', 'write']}
                        disabled={loading}
                        onChange={(lvl) => onSetUserLevel(entry.key, u, (lvl ?? 'read') as AccessLevel)}
                        t={t}
                      />
                    </div>
                  ))}
                </div>
              )}
            </td>
            <td>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => onClear(entry.key)}
                disabled={loading || !overridden}
                title={t('clearTip', 'Clear override (revert to defaults)')}
              >
                {t('clear', 'Clear')}
              </button>
            </td>
          </tr>
        )
      })}
    </>
  )
}

// Segmented off/read/write (or read/write) control. `muted` renders the dimmed
// "this is just reflecting the default" state.
type LevelOption = 'off' | 'read' | 'write'
function LevelControl({
  label,
  value,
  options,
  muted,
  disabled,
  onChange,
  t,
}: {
  label: string
  value: AccessLevel | null
  options: LevelOption[]
  muted?: boolean
  disabled?: boolean
  onChange: (level: AccessLevel | null) => void
  t: (key: string, fallback: string) => string
}) {
  const current: LevelOption = value ?? 'off'
  const labels: Record<LevelOption, string> = {
    off: t('lvlOff', 'Off'),
    read: t('lvlRead', 'Read'),
    write: t('lvlWrite', 'Write'),
  }
  return (
    <div className={`lvl${muted ? ' is-muted' : ''}`} role="group" aria-label={label}>
      {options.map((opt) => (
        <button
          key={opt}
          type="button"
          className={`lvl-seg lvl-${opt}${current === opt ? ' is-on' : ''}`}
          aria-pressed={current === opt}
          disabled={disabled}
          title={labels[opt]}
          onClick={() => onChange(opt === 'off' ? null : opt)}
        >
          {labels[opt]}
        </button>
      ))}
    </div>
  )
}
