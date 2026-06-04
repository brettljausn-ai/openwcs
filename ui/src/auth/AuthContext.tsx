import { createContext, useContext, useEffect, useMemo, useState, ReactNode } from 'react'
import { passwordGrant, decodeJwt } from '../lib/keycloak'
import { configureAuth, installAuthFetch } from '../lib/authFetch'
import { AccessOverrides, ScreenDef, accessibleScreens, canAccess } from './screens'

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
