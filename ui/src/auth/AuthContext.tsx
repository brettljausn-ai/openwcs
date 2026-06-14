import { createContext, useContext, useEffect, useMemo, useState, ReactNode } from 'react'
import { passwordGrant, decodeJwt, refreshTokenGrant } from '../lib/keycloak'
import { configureAuth, installAuthFetch } from '../lib/authFetch'
import { AccessLevel, AccessOverrides, ScreenDef, SCREENS, accessLevel, accessibleScreens, canAccess, canWrite } from './screens'

const STORAGE_KEY = 'openwcs.auth'

interface Session {
  token: string
  refreshToken?: string
  username: string
  name: string
  roles: string[]
}

interface AuthContextValue {
  session: Session | null
  roles: string[]
  username: string
  overrides: AccessOverrides
  login: (username: string, password: string) => Promise<void>
  logout: () => void
  can: (screen: ScreenDef) => boolean
  /** Effective access level on a screen: 'write' | 'read' | null. */
  level: (screen: ScreenDef) => AccessLevel | null
  /** Whether the user may perform writes on a screen (true for WRITE, false for READ/OFF). */
  canWrite: (screen: ScreenDef) => boolean
  /** Convenience for screens that only know their own catalog key: may they write? */
  writeAllowed: (screenKey: string) => boolean
  myScreens: () => ScreenDef[]
}

const AuthContext = createContext<AuthContextValue | null>(null)

function load(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const s = JSON.parse(raw) as Session
    const claims = decodeJwt(s.token)
    if (claims.exp && claims.exp * 1000 < Date.now()) return null // expired
    return s
  } catch {
    return null
  }
}

function sessionFromToken(token: string, refreshToken?: string): Session {
  const c = decodeJwt(token)
  return {
    token,
    refreshToken,
    username: c.preferred_username || c.name || 'user',
    name: c.name || c.preferred_username || 'user',
    roles: c.realm_access?.roles || [],
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => load())
  const [overrides, setOverrides] = useState<AccessOverrides>({})

  // Wire the global fetch interceptor SYNCHRONOUSLY during render — a parent renders before its
  // children's effects run, so the bearer token is in place before WarehouseProvider (or any
  // screen) fires its first API call on reload or right after login. Doing this in an effect
  // raced those child effects: the first call went out tokenless → 401 → "No warehouse access"
  // (interceptor not yet installed) or a logout bounce (post-login fetch with the stale null
  // token). configureAuth/installAuthFetch are idempotent setters, so re-running on every render
  // is safe.
  installAuthFetch()
  configureAuth(
    () => session?.token ?? null,
    () => {
      sessionStorage.removeItem(STORAGE_KEY)
      setSession(null) // 401 on an API call -> drop the session (route guard sends to /login)
    },
  )

  // Persist + load per-screen access overrides (graceful: endpoint may not exist yet).
  useEffect(() => {
    if (!session) {
      setOverrides({})
      return
    }
    let cancelled = false
    fetch('/api/iam/screen-access')
      .then((r) => (r.ok ? r.json() : {}))
      .then((data) => {
        if (!cancelled && data && typeof data === 'object') setOverrides(data as AccessOverrides)
      })
      .catch(() => { /* defaults apply */ })
    return () => {
      cancelled = true
    }
  }, [session])

  // Silently renew the access token before it expires. Keycloak access tokens are short-lived
  // (~minutes), so without this the session "dies" after a while and API calls start 401-ing
  // ("No warehouse access"). Refreshing reschedules itself off the new token.
  useEffect(() => {
    if (!session) return
    const claims = decodeJwt(session.token)
    if (!claims.exp) return
    const delay = Math.max(0, claims.exp * 1000 - Date.now() - 45_000) // ~45s before expiry
    const timer = window.setTimeout(async () => {
      if (!session.refreshToken) return
      try {
        const tok = await refreshTokenGrant(session.refreshToken)
        const next = sessionFromToken(tok.access_token, tok.refresh_token ?? session.refreshToken)
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(next))
        setSession(next)
      } catch {
        // Refresh token expired/invalid → end the session (route guard sends to /login).
        sessionStorage.removeItem(STORAGE_KEY)
        setSession(null)
      }
    }, delay)
    return () => window.clearTimeout(timer)
  }, [session])

  const value = useMemo<AuthContextValue>(() => {
    const roles = session?.roles ?? []
    const username = session?.username ?? ''
    return {
      session,
      roles,
      username,
      overrides,
      async login(u, p) {
        const tok = await passwordGrant(u, p)
        const s = sessionFromToken(tok.access_token, tok.refresh_token)
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s))
        setSession(s)
      },
      logout() {
        sessionStorage.removeItem(STORAGE_KEY)
        setSession(null)
      },
      can: (screen) => canAccess(screen, { roles, username, overrides }),
      level: (screen) => accessLevel(screen, { roles, username, overrides }),
      canWrite: (screen) => canWrite(screen, { roles, username, overrides }),
      writeAllowed: (screenKey) => {
        const screen = SCREENS.find((s) => s.key === screenKey)
        return screen ? canWrite(screen, { roles, username, overrides }) : true
      },
      myScreens: () => accessibleScreens({ roles, username, overrides }),
    }
  }, [session, overrides])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
