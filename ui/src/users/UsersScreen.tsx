import { useEffect, useMemo, useState } from 'react'
import { useT } from '../i18n/useT'
import InfoTip from '../ui/InfoTip'
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
  const t = useT('users')
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
    if (!confirm(t('confirmDelete', 'Delete user "{name}"? This cannot be undone.').replace('{name}', u.username))) return
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
        <div className="eyebrow">{t('eyebrow', 'Administration')}</div>
        <h1>{t('title', 'User management')}</h1>
        <p>{t('subtitle', 'Manage Keycloak users, their realm roles and credentials for the openWCS realm.')}</p>
      </div>

      {error && <div className="alert alert-danger">{error}</div>}

      <div className="toolbar">
        <form onSubmit={onSearch} style={{ display: 'flex', gap: '.6rem', flex: 1, maxWidth: 460 }}>
          <input
            className="form-control"
            placeholder={t('searchPlaceholder', 'Search by username, email or name…')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <button type="submit" className="btn btn-ghost" disabled={loading}>
            {t('search', 'Search')}
          </button>
        </form>
        <div className="spacer" />
        <button className="btn btn-ghost" onClick={() => refresh()} disabled={loading}>
          {loading ? <span className="spin" /> : t('refresh', 'Refresh')}
        </button>
        <button className="btn btn-primary" onClick={() => setEdit({ mode: 'create' })}>
          {t('newUser', '+ New user')}
        </button>
      </div>

      <div className="glass" style={{ overflow: 'hidden' }}>
        <table>
          <thead>
            <tr>
              <th>{t('colUsername', 'Username')}</th>
              <th>{t('colName', 'Name')}</th>
              <th>{t('colEmail', 'Email')}</th>
              <th>{t('colStatus', 'Status')}</th>
              <th style={{ textAlign: 'right' }}>{t('colActions', 'Actions')}</th>
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
                      <span className="badge badge-success">{t('enabled', 'Enabled')}</span>
                    ) : (
                      <span className="badge badge-danger">{t('disabled', 'Disabled')}</span>
                    )}
                  </td>
                  <td>
                    <div style={{ display: 'flex', gap: '.4rem', justifyContent: 'flex-end', flexWrap: 'wrap' }}>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setRolesTarget(u)}>
                        {t('roles', 'Roles')}
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setEdit({ mode: 'edit', user: u })}>
                        {t('edit', 'Edit')}
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => setPwTarget(u)}>
                        {t('password', 'Password')}
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy} onClick={() => toggleEnabled(u)}>
                        {u.enabled ? t('disable', 'Disable') : t('enable', 'Enable')}
                      </button>
                      <button className="btn btn-danger btn-sm" disabled={busy} onClick={() => onDelete(u)}>
                        {t('delete', 'Delete')}
                      </button>
                    </div>
                  </td>
                </tr>
              )
            })}
            {users.length === 0 && !loading && (
              <tr>
                <td colSpan={5} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                  {t('noUsers', 'No users found.')}
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
  const t = useT('users')
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
      { value: '', label: t('none', '— none —') },
      ...warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` })),
    ],
    [warehouses, t],
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
        <h2>{isEdit ? t('editUser', 'Edit user') : t('newUserTitle', 'New user')}</h2>
        {error && <div className="alert alert-danger">{error}</div>}

        <div style={{ marginBottom: '.9rem' }}>
          <label>{t('username', 'Username')} <InfoTip text={t('usernameTip', 'The unique login name for this Keycloak account. Cannot be changed after the user is created.')} example="jdoe" /></label>
          <input
            className="form-control"
            value={form.username}
            disabled={isEdit}
            required
            onChange={(e) => set('username', e.target.value)}
          />
        </div>
        <div style={{ marginBottom: '.9rem' }}>
          <label>{t('email', 'Email')} <InfoTip text={t('emailTip', "The user's email address, used for notifications and account-recovery flows. Optional.")} example="jane.doe@example.com" /></label>
          <input
            className="form-control"
            type="email"
            value={form.email}
            onChange={(e) => set('email', e.target.value)}
          />
        </div>
        <div style={{ display: 'flex', gap: '.9rem', marginBottom: '.9rem' }}>
          <div style={{ flex: 1 }}>
            <label>{t('firstName', 'First name')} <InfoTip text={t('firstNameTip', "The user's given name, shown in the user list and across the app. Optional.")} example="Jane" /></label>
            <input className="form-control" value={form.firstName} onChange={(e) => set('firstName', e.target.value)} />
          </div>
          <div style={{ flex: 1 }}>
            <label>{t('lastName', 'Last name')} <InfoTip text={t('lastNameTip', "The user's family name, shown in the user list and across the app. Optional.")} example="Doe" /></label>
            <input className="form-control" value={form.lastName} onChange={(e) => set('lastName', e.target.value)} />
          </div>
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: '.5rem', cursor: 'pointer' }}>
          <input type="checkbox" checked={form.enabled} onChange={(e) => set('enabled', e.target.checked)} />
          {t('enabledLabel', 'Enabled')} <InfoTip text={t('enabledTip', 'When on, the user can sign in. Turn off to disable the account without deleting it.')} example="On" />
        </label>

        {isEdit && (
          <div style={{ marginTop: '.9rem' }}>
            <label>{t('defaultWarehouse', 'Default warehouse')} <InfoTip text={t('defaultWarehouseTip', 'The warehouse pre-selected for this user after login; choosing one also grants them access to it. Leave as none for no default.')} example="WH01 — Main DC" /></label>
            <Select
              value={defaultWarehouse}
              onChange={setDefaultWarehouse}
              options={warehouseOptions}
              ariaLabel={t('defaultWarehouse', 'Default warehouse')}
              placeholder={t('none', '— none —')}
            />
          </div>
        )}

        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={saving}>
            {t('cancel', 'Cancel')}
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving || !form.username.trim()}>
            {saving ? <span className="spin" /> : isEdit ? t('saveChanges', 'Save changes') : t('createUser', 'Create user')}
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
  const t = useT('users')
  const [password, setPassword] = useState('')
  const [confirmPw, setConfirmPw] = useState('')
  // Default OFF: a temporary password forces an action the password-grant login can't satisfy, so it
  // would leave the account "not fully set up". Admins can still opt in; users change it themselves
  // via the login-screen "Change password" flow.
  const [temporary, setTemporary] = useState(false)
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
        <h2>{t('setPassword', 'Set password')}</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          {t('for', 'For')} <strong>{user.username}</strong>
        </p>
        {error && <div className="alert alert-danger">{error}</div>}
        {done && <div className="alert" style={{ background: 'rgba(141,198,63,.12)', color: '#8DC63F' }}>{t('passwordUpdated', 'Password updated.')}</div>}

        <div style={{ marginBottom: '.9rem' }}>
          <label>{t('newPassword', 'New password')} <InfoTip text={t('newPasswordTip', "The new credential to set for this user. Should meet the realm's password policy (length, complexity).")} example="Wcs!Spring2026" /></label>
          <input
            className="form-control"
            type="password"
            value={password}
            autoComplete="new-password"
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <div style={{ marginBottom: '.9rem' }}>
          <label>{t('confirmPassword', 'Confirm password')} <InfoTip text={t('confirmPasswordTip', 'Re-enter the new password exactly to confirm there were no typos.')} example="Wcs!Spring2026" /></label>
          <input
            className="form-control"
            type="password"
            value={confirmPw}
            autoComplete="new-password"
            onChange={(e) => setConfirmPw(e.target.value)}
          />
          {mismatch && <div className="alert alert-danger" style={{ marginBottom: 0 }}>{t('passwordsMismatch', 'Passwords do not match.')}</div>}
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: '.5rem', cursor: 'pointer' }}>
          <input type="checkbox" checked={temporary} onChange={(e) => setTemporary(e.target.checked)} />
          {t('temporary', 'Temporary (user must change at next login)')} <InfoTip text={t('temporaryTip', 'When on, this password is one-time only and the user is forced to set their own at next sign-in.')} example="On" />
        </label>

        <div className="dialog-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={saving}>
            {t('cancel', 'Cancel')}
          </button>
          <button type="submit" className="btn btn-primary" disabled={saving || !password || mismatch}>
            {saving ? <span className="spin" /> : t('setPassword', 'Set password')}
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
  const t = useT('users')
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
      setError(t('roleMissing', 'Realm role "{role}" does not exist in this realm.').replace('{role}', roleName))
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
        <h2>{t('realmRoles', 'Realm roles')}</h2>
        <p className="muted" style={{ marginTop: 0 }}>
          {t('for', 'For')} <strong>{user.username}</strong>
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
                <th>{t('roleCol', 'Role')}</th>
                <th>{t('stateCol', 'State')}</th>
                <th style={{ textAlign: 'right' }}>{t('actionCol', 'Action')}</th>
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
                        <span className="badge badge-success">{t('assigned', 'Assigned')}</span>
                      ) : exists ? (
                        <span className="badge badge-info">{t('available', 'Available')}</span>
                      ) : (
                        <span className="badge badge-warning">{t('missing', 'Missing')}</span>
                      )}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <button
                        className={`btn btn-sm ${has ? 'btn-danger' : 'btn-ghost'}`}
                        disabled={isBusy || !exists}
                        onClick={() => toggle(roleName, has)}
                      >
                        {isBusy ? <span className="spin" /> : has ? t('remove', 'Remove') : t('assign', 'Assign')}
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
            {t('done', 'Done')}
          </button>
        </div>
      </div>
    </div>
  )
}
