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

/**
 * Self-service password change (login screen). Public endpoint: works even for an account that is
 * "not fully set up" (a forced/temporary password), which can't obtain a token to change it
 * in-app. The iam service verifies the current password and sets the new one as permanent.
 */
export async function changePassword(
  username: string,
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const res = await fetch('/api/iam/change-password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, currentPassword, newPassword }),
  })
  if (!res.ok) {
    let msg = 'Could not change the password'
    try {
      const j = await res.json()
      if (j.detail) msg = j.detail
    } catch {
      /* keep default */
    }
    throw new Error(msg)
  }
}

/** Exchange a refresh token for a fresh access token (silent session renewal). */
export async function refreshTokenGrant(refreshToken: string): Promise<TokenResponse> {
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: KC_CLIENT,
    refresh_token: refreshToken,
  })
  const res = await fetch(TOKEN_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!res.ok) {
    throw new Error('Session refresh failed')
  }
  return res.json()
}
