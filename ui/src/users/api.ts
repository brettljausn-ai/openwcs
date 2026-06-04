// Keycloak Admin REST client (same-origin under /admin/realms/openwcs/...).
//
// These calls go straight to Keycloak's admin API: the nginx proxy (prod) and the vite
// dev proxy both forward /admin -> Keycloak, and the global fetch interceptor
// (lib/authFetch.ts) attaches the logged-in admin's Bearer token, whose realm-management
// roles authorise these operations. We therefore use bare fetch('/admin/realms/...').

const REALM = 'openwcs'
const BASE = `/admin/realms/${REALM}`

/** Realm roles the UI knows how to assign (matches auth/screens.ts Role union). */
export const MANAGED_ROLES = ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER'] as const
export type ManagedRole = (typeof MANAGED_ROLES)[number]

export interface KcUser {
  id: string
  username: string
  email?: string
  firstName?: string
  lastName?: string
  enabled: boolean
  emailVerified?: boolean
  createdTimestamp?: number
}

export interface KcRole {
  id: string
  name: string
  description?: string
  composite?: boolean
  clientRole?: boolean
  containerId?: string
}

export interface NewUser {
  username: string
  email?: string
  firstName?: string
  lastName?: string
  enabled: boolean
}

// --- low-level helpers -----------------------------------------------------

/** Throw a readable Error from a failed Keycloak response (it often returns
    {error,errorMessage} or a plain string). */
async function fail(res: Response): Promise<never> {
  let detail = ''
  try {
    const text = await res.text()
    if (text) {
      try {
        const j = JSON.parse(text)
        detail = j.errorMessage || j.error_description || j.error || text
      } catch {
        detail = text
      }
    }
  } catch {
    /* ignore body read errors */
  }
  throw new Error(`${res.status} ${res.statusText}${detail ? ` — ${detail}` : ''}`)
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { headers: { Accept: 'application/json' } })
  if (!res.ok) await fail(res)
  return (await res.json()) as T
}

async function send(method: string, path: string, body?: unknown): Promise<Response> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) await fail(res)
  return res
}

// --- users -----------------------------------------------------------------

export function listUsers(search?: string): Promise<KcUser[]> {
  const q = new URLSearchParams({ max: '200', briefRepresentation: 'true' })
  if (search && search.trim()) q.set('search', search.trim())
  return getJson<KcUser[]>(`/users?${q.toString()}`)
}

/** Server-side user search with pagination — for lists that can reach thousands of users. */
export function searchUsers(opts: { search?: string; first?: number; max?: number }): Promise<KcUser[]> {
  const q = new URLSearchParams({
    first: String(opts.first ?? 0),
    max: String(opts.max ?? 20),
    briefRepresentation: 'true',
  })
  if (opts.search && opts.search.trim()) q.set('search', opts.search.trim())
  return getJson<KcUser[]>(`/users?${q.toString()}`)
}

/** Total users matching an optional search — drives server-side pagination. */
export function countUsers(search?: string): Promise<number> {
  const q = new URLSearchParams()
  if (search && search.trim()) q.set('search', search.trim())
  const suffix = q.toString() ? `?${q.toString()}` : ''
  return getJson<number>(`/users/count${suffix}`)
}

export function getUser(id: string): Promise<KcUser> {
  return getJson<KcUser>(`/users/${id}`)
}

/** Create a user. Keycloak returns 201 with a Location header; we parse the new id from it. */
export async function createUser(user: NewUser): Promise<string> {
  const res = await send('POST', '/users', user)
  const loc = res.headers.get('Location')
  return loc ? loc.substring(loc.lastIndexOf('/') + 1) : ''
}

export async function updateUser(id: string, patch: Partial<KcUser>): Promise<void> {
  await send('PUT', `/users/${id}`, patch)
}

export async function deleteUser(id: string): Promise<void> {
  await send('DELETE', `/users/${id}`)
}

export async function setEnabled(id: string, enabled: boolean): Promise<void> {
  await send('PUT', `/users/${id}`, { enabled })
}

export async function resetPassword(id: string, password: string, temporary: boolean): Promise<void> {
  await send('PUT', `/users/${id}/reset-password`, { type: 'password', value: password, temporary })
}

// --- realm role mappings ---------------------------------------------------

/** Roles currently assigned to the user (realm-level). */
export function listAssignedRealmRoles(id: string): Promise<KcRole[]> {
  return getJson<KcRole[]>(`/users/${id}/role-mappings/realm`)
}

/** Realm roles available to assign to the user (not yet assigned). */
export function listAvailableRealmRoles(id: string): Promise<KcRole[]> {
  return getJson<KcRole[]>(`/users/${id}/role-mappings/realm/available`)
}

export async function assignRealmRoles(id: string, roles: KcRole[]): Promise<void> {
  if (roles.length === 0) return
  await send('POST', `/users/${id}/role-mappings/realm`, roles)
}

export async function removeRealmRoles(id: string, roles: KcRole[]): Promise<void> {
  if (roles.length === 0) return
  await send('DELETE', `/users/${id}/role-mappings/realm`, roles)
}
