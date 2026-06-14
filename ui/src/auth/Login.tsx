import { FormEvent, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './AuthContext'
import { changePassword } from '../lib/keycloak'

// The login screen stays English on purpose (it renders before the language provider).
export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from || '/'

  const [mode, setMode] = useState<'signin' | 'change'>('signin')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Change-password fields.
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    setBusy(true)
    try {
      await login(username.trim(), password)
      navigate(from, { replace: true })
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Sign-in failed'
      // A correct password on an account awaiting a forced change: send them to the change form.
      if (/not fully set up/i.test(msg)) {
        setCurrentPassword(password)
        setMode('change')
        setNotice('Your account needs a new password before you can sign in. Set one below.')
      } else {
        setError(msg)
      }
    } finally {
      setBusy(false)
    }
  }

  async function submitChange(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    if (newPassword.length < 8) {
      setError('The new password must be at least 8 characters.')
      return
    }
    if (newPassword !== confirmPassword) {
      setError('The new passwords do not match.')
      return
    }
    setBusy(true)
    try {
      await changePassword(username.trim(), currentPassword, newPassword)
      // Back to sign-in, password prefilled, ready to log in with the new credential.
      setPassword(newPassword)
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      setMode('signin')
      setNotice('Password changed. You can sign in now.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not change the password')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <div className="login-orb" aria-hidden="true" />
      {mode === 'signin' ? (
        <form className="glass login-card" onSubmit={submit}>
          <div className="login-brand">
            <img src="/Logo_white_solo.png" alt="" />
            <span className="login-wordmark">open<span className="accent">WCS</span></span>
          </div>
          <div className="login-sub">Warehouse Control System — sign in to continue</div>

          {error && <div className="alert alert-danger">{error}</div>}
          {notice && <div className="alert">{notice}</div>}

          <div className="login-field">
            <label htmlFor="u">Username</label>
            <input id="u" className="form-control" autoFocus autoComplete="username"
                   value={username} onChange={(e) => setUsername(e.target.value)} required />
          </div>
          <div className="login-field">
            <label htmlFor="p">Password</label>
            <input id="p" type="password" className="form-control" autoComplete="current-password"
                   value={password} onChange={(e) => setPassword(e.target.value)} required />
          </div>

          <button className="btn btn-primary btn-block btn-lg" type="submit" disabled={busy}>
            {busy ? <span className="spin" /> : 'Sign in'}
          </button>

          <button type="button" className="login-link"
                  onClick={() => { setMode('change'); setError(null); setNotice(null) }}>
            Change password
          </button>

          <div className="login-hint">Authenticated by Keycloak · openwcs realm</div>
        </form>
      ) : (
        <form className="glass login-card" onSubmit={submitChange}>
          <div className="login-brand">
            <img src="/Logo_white_solo.png" alt="" />
            <span className="login-wordmark">open<span className="accent">WCS</span></span>
          </div>
          <div className="login-sub">Change your password</div>

          {error && <div className="alert alert-danger">{error}</div>}
          {notice && <div className="alert">{notice}</div>}

          <div className="login-field">
            <label htmlFor="cu">Username</label>
            <input id="cu" className="form-control" autoComplete="username"
                   value={username} onChange={(e) => setUsername(e.target.value)} required />
          </div>
          <div className="login-field">
            <label htmlFor="cp">Current password</label>
            <input id="cp" type="password" className="form-control" autoComplete="current-password"
                   value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} required />
          </div>
          <div className="login-field">
            <label htmlFor="np">New password</label>
            <input id="np" type="password" className="form-control" autoComplete="new-password"
                   value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required />
          </div>
          <div className="login-field">
            <label htmlFor="cf">Confirm new password</label>
            <input id="cf" type="password" className="form-control" autoComplete="new-password"
                   value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} required />
          </div>

          <button className="btn btn-primary btn-block btn-lg" type="submit" disabled={busy}>
            {busy ? <span className="spin" /> : 'Change password'}
          </button>

          <button type="button" className="login-link"
                  onClick={() => { setMode('signin'); setError(null); setNotice(null) }}>
            Back to sign in
          </button>

          <div className="login-hint">At least 8 characters. You set this yourself, no admin needed.</div>
        </form>
      )}
    </div>
  )
}
