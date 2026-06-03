// Keycloak endpoints (same-origin via the nginx proxy in prod; vite proxies them in dev).
export const KC_REALM = 'openwcs'
export const KC_CLIENT = 'openwcs-web'
export const TOKEN_URL = `/realms/${KC_REALM}/protocol/openid-connect/token`
export const LOGOUT_URL = `/realms/${KC_REALM}/protocol/openid-connect/logout`
// Admin REST API base for user management (token must carry realm-management roles).
export const ADMIN_BASE = `/admin/realms/${KC_REALM}`

export interface JwtClaims {
  preferred_username?: string
  name?: string
  email?: string
  exp?: number
  realm_access?: { roles?: string[] }
}

/** Decode a JWT payload without verifying (the gateway verifies; the UI only reads claims). */
export function decodeJwt(token: string): JwtClaims {
  try {
    const payload = token.split('.')[1]
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    return JSON.parse(decodeURIComponent(escape(json)))
  } catch {
    return {}
  }
}

export interface TokenResponse {
  access_token: string
  refresh_token?: string
  expires_in?: number
}

/** Resource Owner Password Credentials grant against the public openwcs-web client. */
export async function passwordGrant(username: string, password: string): Promise<TokenResponse> {
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: KC_CLIENT,
    username,
    password,
  })
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) {
    let msg = 'Invalid username or password'
    try {
      const j = await res.json()
      if (j.error_description) msg = j.error_description
    } catch { /* keep default */ }
    throw new Error(msg)
  }
  return res.json()
}
