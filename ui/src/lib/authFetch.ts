// Global fetch interceptor: attaches the Bearer token to same-origin API calls
// (/api/** -> gateway, /admin/** -> Keycloak admin) and reports 401s so the app can
// log out. This lets every existing api.ts keep using bare fetch('/api/...') unchanged.
//
// The token endpoint (/realms/.../token) is deliberately NOT matched — logging in must
// not send a stale Bearer.

let getToken: () => string | null = () => null
let onUnauthorized: () => void = () => {}
let installed = false

function needsAuth(url: string): boolean {
  // Match path regardless of absolute/relative form.
  const path = url.startsWith('http') ? new URL(url).pathname : url
  return path.startsWith('/api/') || path.startsWith('/admin/')
}

export function configureAuth(tokenGetter: () => string | null, unauthorized: () => void) {
  getToken = tokenGetter
  onUnauthorized = unauthorized
}

export function installAuthFetch() {
  if (installed) return
  installed = true
  const native = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const token = getToken()
    let nextInit = init
    if (token && needsAuth(url)) {
      const headers = new Headers(init?.headers || (typeof input !== 'string' && !(input instanceof URL) ? input.headers : undefined))
      if (!headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
      nextInit = { ...init, headers }
    }
    const res = await native(input, nextInit)
    if (res.status === 401 && needsAuth(url)) onUnauthorized()
    return res
  }
}
