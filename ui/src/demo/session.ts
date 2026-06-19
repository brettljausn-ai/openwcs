// Synthetic read-only session for the public live demo (VITE_DEMO builds only).
//
// In demo mode there is no Keycloak and no real backend: AuthContext seeds this fixed session so
// RequireAuth passes and /login is never shown. The token is a placeholder string (never sent
// anywhere: the fetch interceptor routes /api and /admin to the in-browser mock). Read-only is
// enforced in AuthContext by short-circuiting can()/canWrite() rather than relying on role math.

import meta from './fixtures/meta.json'

export interface DemoSession {
  token: string
  username: string
  name: string
  roles: string[]
}

// VIEWER-style roles only. can()/canWrite() are overridden in demo mode regardless, but a read-only
// role keeps the intent obvious and avoids any accidental write path if an override is missed.
export const DEMO_SESSION: DemoSession = {
  token: 'demo',
  username: 'demo',
  name: 'Demo User',
  roles: ['VIEWER'],
}

// The single warehouse the snapshot is scoped to. Used to pre-seed the warehouse context and the
// pick engine so every ?warehouseId= lookup resolves against the fixtures.
export const DEMO_WAREHOUSE_ID: string = meta.warehouseId
export const DEMO_WAREHOUSE_CODE: string = meta.warehouseCode
export const DEMO_WAREHOUSE_NAME: string = meta.warehouseName

// Build-time flag. Vite inlines import.meta.env.VITE_DEMO as the string "true" when set. The
// optional chain keeps this defined in non-Vite runtimes (e.g. the node test runner), where
// import.meta.env is undefined; there IS_DEMO is simply false.
export const IS_DEMO: boolean = import.meta.env?.VITE_DEMO === 'true'
