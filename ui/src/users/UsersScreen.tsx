import { useEffect, useMemo, useState } from 'react'
import Select from '../ui/Select'
import { Warehouse, getAccess, listWarehouses, setAccess } from '../warehouseaccess/api'
import {
  KcRole,
  KcUser,
  MANAGED_ROLES,
  ManagedRole,
  NewUser,
  assignRealmRoles,
  createUser,
  deleteUser,
  listAssignedRealmRoles,
  listAvailableRealmRoles,
  listUsers,
  removeRealmRoles,
  resetPassword,
  setEnabled,
  updateUser,
} from './api'

// User management (Administration · ADMIN). CRUD over the Keycloak admin REST API,
// plus realm-role assignment for the four managed roles (ADMIN/SUPERVISOR/OPERATOR/VIEWER).

type EditTarget = { mode: 'create' } | { mode: 'edit'; user: KcUser }

export default function UsersScreen() {
  const [users, setUsers] = useState<KcUser[]>([])
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const [edit, setEdit] = useState<EditTarget | null>(null)
  const [pwTarget, setPwTarget] = useState<KcUser | null>(null)
  const [rolesTarget, setRolesTarget] = useState<KcUser | null>(null)

  async function refresh(term = search) {
    setLoading(true)
    setError(null)
    try {
      const list = await listUsers(term)
      list.sort((a, b) => a.username.localeCompare(b.username))
      setUsers(list)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    refresh('')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function onSearch(e: React.FormEvent) {
    e.preventDefault()
    refresh()
  }

  async function toggleEnabled(u: KcUser) {
    setBusyId(u.id)
    setError(null)
    try {
      await setEnabled(u.id, !u.enabled)
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyId(null)
    }
  }

  async function onDelete(u: KcUser) {
    if (!confirm(`Delete user "${u.username}"? This cannot be undone.`)) return
    setBusyId(u.id)
    setError(null)
    try {
      await deleteUser(u.id)
      await refresh()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">Administration</div>
        <h1>User management</h1>
        <p>Manage Keycloak users, their realm roles and credentials for the openWCS realm.</p>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="toolbar">
        <form onSubmit={onSearch} style={{ display: 'flex', gap: '.6rem', flex: 1, maxWidth: 460 }}>
          <input
            className="form-control"
            placeholder="Search by username, email or name…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button type="submit" className="btn btn-ghost" disabled={loading}>
            Search
          </button>
        </form>
        <div className="spacer" />
        <button className="btn btn-ghost" onClick={() => refresh()} disabled={loading}>
          {loading ? <span className="spin" /> : 'Refresh'}
        </button>
        <button className="btn btn-primary" onClick={() => setEdit({ mode: 'create' })}>
          + New user
        </button>
      </div>

      <div className="glass" style={{ overflow: 'hidden' }}>
        <table>
          <thead>
            <tr>
              <th>Username</th>
              <th>Name</th>
              <th>Email</th>
              <th>Status</th>
              <th style={{ textAlign: 'right' }}>Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.map((u) => {
              const busy = busyId === u.id
              const name = [u.firstName, u.lastName].filter(Boolean).join(' ')
              return (
                <tr key={u.id}>
                  <td style={{ fontFamily: 'var(--font-mono)' }}>{u.username}</td>
                  <td>{name || <span className="muted">—</span>}</td>
                  <td>{u.email || <span className="muted">—</span>}</td>
                  <td>
                    {u.enabled ? (
                      <span className="badge badge-success">Enabled</span>
                    ) : (
                      <span className="badge badge-danger">Disabled</span>
                    )}
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '.4rem', justifyContent: 'flex-end', flexWrap: 'wrap' }}>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setRolesTarget(u)}>
                        Roles
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setEdit({ mode: 'edit', user: u })}>
                        Edit
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setPwTarget(u)}>
                        Password
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => toggleEnabled(u)}>
                        {u.enabled ? 'Disable' : 'Enable'}
                      </button>
                      <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => onDelete(u)}>
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              )
            })}
            {users.length === 0 && !loading && (
              <tr>
                <td colSpan={5} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                  No users found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {edit && (
        <UserDialog
          target={edit}
          onClose={() => setEdit(null)}
          onSaved={() => {
            setEdit(null)
            refresh()
          }}
        />
      )}
      {pwTarget && (
        <PasswordDialog
          user={pwTarget}
          onClose={() => setPwTarget(null)}
          onSaved={() => setPwTarget(null)}
        />
      )}
      {rolesTarget && (
        <RolesDialog user={rolesTarget} onClose={() => setRolesTarget(null)} />
      )}
    </div>
  )
}

// --------------------------------------------------------------------------
// Create / edit dialog
// --------------------------------------------------------------------------

function UserDialog({
  target,
  onClose,
  onSaved,
}: {
  target: EditTarget
  onClose: () => void
  onSaved: () => void
}) {
  const isEdit = target.mode === 'edit'
  const existing = target.mode === 'edit' ? target.user : null
  const [form, setForm] = useState<NewUser>({
    username: existing?.username ?? '',
    email: existing?.email ?? '',
    firstName: existing?.firstName ?? '',
    lastName: existing?.lastName ?? '',
    enabled: existing?.enabled ?? true,
  })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Default-warehouse selector. Options come from master-data; the loaded access (allowed set +
  // current default) is only fetched in edit mode, where the username already exists.
  const [warehouses, setWarehouses] = useState<Warehouse[]>([])
  const [defaultWarehouse, setDefaultWarehouse] = useState('')
  const [loadedAccess, setLoadedAccess] = useState<{ warehouses: string[]; defaultWarehouse: string | null } | null>(null)

  function set<K extends keyof NewUser>(key: K, value: NewUser[K]) {
    setForm((f) => ({ ...f, [key]: value }))
  }

  // Load the warehouse list once (graceful: no options on failure).
  useEffect(() => {
    let cancelled = false
    listWarehouses()
      .then((ws) => { if (!cancelled) setWarehouses(ws) })
      .catch(() => { if (!cancelled) setWarehouses([]) })
    return () => { cancelled = true }
  }, [])

  // In edit mode, preselect the user's current default warehouse.
  useEffect(() => {
    if (!existing) return
    let cancelled = false
    getAccess(existing.username)
      .then((acc) => {
        if (cancelled) return
        setLoadedAccess(acc)
        setDefaultWarehouse(acc.defaultWarehouse ?? '')
      })
      .catch(() => { /* no current access — leave default unset */ })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const warehouseOptions = useMemo(
    () => [
      { value: '', label: '— none —' },
      ...warehouses.map((w) => ({ value: w.code, label: `${w.code} — ${w.name}` })),
    ],
    [warehouses],
  )

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      const payload: NewUser = {
        username: form.username.trim(),
        email: form.email?.trim() || undefined,
        firstName: form.firstName?.trim() || undefined,
        lastName: form.lastName?.trim() || undefined,
        enabled: form.enabled,
      }
      if (isEdit && existing) {
        await updateUser(existing.id, payload)
        // Persist the default warehouse if it changed. Setting a default auto-adds the user to that
        // warehouse's allowed list (union with the previously-loaded allowed set).
        const loadedDefault = loadedAccess?.defaultWarehouse ?? ''
        if (defaultWarehouse !== loadedDefault) {
          const allowed = new Set(loadedAccess?.warehouses ?? [])
          if (defaultWarehouse) allowed.add(defaultWarehouse)
          await setAccess(existing.username, {
            warehouses: Array.from(allowed),
            defaultWarehouse: defaultWarehouse || null,
          })
        }
      } else {
        await createUser(payload)
      }
      onSaved()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <form className="dialog" onMouseDown={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2>{isEdit ? 'Edit user' : 'New user'}</h2>
        {error && <div className="alert alert-danger">{error}</div>}

        <div style={{ marginBottom: '.9rem' }}>
          <label>Username</label>
          <input
            className="form-control"
            value={form.username}
            disabled={isEdit}
            required
            onChange={(e) => set('username', e.target.value)}
          />
        </div>
        <div style={{ marginBottom: '.9rem' }}>
          <label>Email</label>
          <input
            className="form-control"
            type="email"
            value={form.email}
            onChange={(e) => set('email', e.target.value)}
          />
        </div>
        <div style={{ display: 'flex', gap: '.9rem', marginBottom: '.9rem' }}>
          <div style={{ flex: 1 }}>
            <label>First name</label>
            <input className="form-control" value={form.firstName} onChange={(e) => set('firstName', e.target.value)} />
          </div>
          <div style={{ flex: 1 }}>
            <label>Last name</label>
            <input className="form-control" value={form.lastName} onChange={(e) => set('lastName', e.target.value)} />
          </div>
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: '.5rem', cursor: 'pointer' }}>
          <input type="checkbox" checked={form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
          Enabled
        </label>

        {isEdit && (
          <div style={{ marginTop: '.9rem' }}>
            <label>Default warehouse</label>
            <Select
              value={defaultWarehouse}
              onChange={setDefaultWarehouse}
              options={warehouseOptions}
              ariaLabel="Default warehouse"
              placeholder="— none —"
            />
          </div>
        )}

        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving || !form.username.trim()}>
            {saving ? <span className="spin" /> : isEdit ? 'Save changes' : 'Create user'}
          </button>
        </div>
      </form>
    </div>
  )
}

// --------------------------------------------------------------------------
// Set / reset password dialog
// --------------------------------------------------------------------------

function PasswordDialog({
  user,
  onClose,
  onSaved,
}: {
  user: KcUser
  onClose: () => void
  onSaved: () => void
}) {
  const [password, setPassword] = useState('')
  const [confirmPw, setConfirmPw] = useState('')
  const [temporary, setTemporary] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const mismatch = confirmPw.length > 0 && password !== confirmPw

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (mismatch || !password) return
    setSaving(true)
    setError(null)
    try {
      await resetPassword(user.id, password, temporary)
      setDone(true)
      setTimeout(onSaved, 900)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setSaving(false)
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <form className="dialog" onMouseDown={(e) => e.stopPropagation()} onSubmit={submit}>
        <h2>Set password</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          For <strong>{user.username}</strong>
        </p>
        {error && <div className="alert alert-danger">{error}</div>}
        {done && <div className="alert" style={{ background: 'rgba(141,198,63,.12)', color: '#8DC63F' }}>Password updated.</div>}

        <div style={{ marginBottom: '.9rem' }}>
          <label>New password</label>
          <input
            className="form-control"
            type="password"
            value={password}
            autoComplete="new-password"
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <div style={{ marginBottom: '.9rem' }}>
          <label>Confirm password</label>
          <input
            className="form-control"
            type="password"
            value={confirmPw}
            autoComplete="new-password"
            onChange={(e) => setConfirmPw(e.target.value)}
          />
          {mismatch && <div className="alert alert-danger" style={{ marginBottom: 0 }}>Passwords do not match.</div>}
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: '.5rem', cursor: 'pointer' }}>
          <input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} />
          Temporary (user must change at next login)
        </label>

        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving || !password || mismatch}>
            {saving ? <span className="spin" /> : 'Set password'}
          </button>
        </div>
      </form>
    </div>
  )
}

// --------------------------------------------------------------------------
// Realm roles dialog
// --------------------------------------------------------------------------

function RolesDialog({ user, onClose }: { user: KcUser; onClose: () => void }) {
  const [assigned, setAssigned] = useState<KcRole[]>([])
  const [available, setAvailable] = useState<KcRole[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Index of role name -> KcRole, from both assigned & available, so we can build the
  // exact payload Keycloak's role-mappings endpoints expect (full role representation).
  const roleByName = useMemo(() => {
    const m = new Map<string, KcRole>()
    for (const r of [...assigned, ...available]) m.set(r.name, r)
    return m
  }, [assigned, available])

  const assignedNames = useMemo(() => new Set(assigned.map((r) => r.name)), [assigned])

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const [asg, avail] = await Promise.all([
        listAssignedRealmRoles(user.id),
        listAvailableRealmRoles(user.id),
      ])
      setAssigned(asg)
      setAvailable(avail)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function toggle(roleName: ManagedRole, nowAssigned: boolean) {
    const role = roleByName.get(roleName)
    if (!role) {
      setError(`Realm role "${roleName}" does not exist in this realm.`)
      return
    }
    setBusy(roleName)
    setError(null)
    try {
      if (nowAssigned) await removeRealmRoles(user.id, [role])
      else await assignRealmRoles(user.id, [role])
      await load()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="dialog" onMouseDown={(e) => e.stopPropagation()}>
        <h2>Realm roles</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          For <strong>{user.username}</strong>
        </p>
        {error && <div className="alert alert-danger">{error}</div>}

        {loading ? (
          <div style={{ display: 'flex', justifyContent: 'center', padding: '1.5rem' }}>
            <span className="spin" />
          </div>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Role</th>
                <th>State</th>
                <th style={{ textAlign: 'right' }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {MANAGED_ROLES.map((roleName) => {
                const has = assignedNames.has(roleName)
                const exists = roleByName.has(roleName)
                const isBusy = busy === roleName
                return (
                  <tr key={roleName}>
                    <td style={{ fontFamily: 'var(--font-mono)' }}>{roleName}</td>
                    <td>
                      {has ? (
                        <span className="badge badge-success">Assigned</span>
                      ) : exists ? (
                        <span className="badge badge-info">Available</span>
                      ) : (
                        <span className="badge badge-warning">Missing</span>
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        className={`btn btn-sm ${has ? 'btn-danger' : 'btn-ghost'}`}
                        disabled={isBusy || !exists}
                        onClick={() => toggle(roleName, has)}
                      >
                        {isBusy ? <span className="spin" /> : has ? 'Remove' : 'Assign'}
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}

        <div className="dialog-actions">
          <button type="button" className="btn btn-primary" onClick={onClose}>
            Done
          </button>
        </div>
      </div>
    </div>
  )
}
