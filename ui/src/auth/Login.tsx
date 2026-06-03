import { FormEvent, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './AuthContext'

export default function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from || '/'

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(username.trim(), password)
      navigate(from, { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign-in failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <div className="login-orb" aria-hidden="true" />
      <form className="glass login-card" onSubmit={submit}>
        <div className="login-brand">
          <img src="/Logo_white_solo.png" alt="" />
          openWCS
        </div>
        <div className="login-sub">Warehouse Control System — sign in to continue</div>

        {error && <div className="alert alert-danger">{error}</div>}

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

        <div className="login-hint">Authenticated by Keycloak · openwcs realm</div>
      </form>
    </div>
  )
}
