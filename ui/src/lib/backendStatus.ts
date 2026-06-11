// Tiny global signal for whether the backend (the API gateway) is reachable.
//
// The fetch interceptor (authFetch.ts) flips this to "down" when an /api call returns a gateway error
// (502/503/504) or the request throws (server unreachable — e.g. a restart), and back to "up" on the
// next reachable response. BackendOverlay subscribes to show a friendly reconnecting screen while down.

type Listener = (down: boolean) => void

let down = false
const listeners = new Set<Listener>()

export function isBackendDown(): boolean {
  return down
}

export function setBackendDown(value: boolean): void {
  if (down === value) return
  down = value
  for (const l of listeners) l(down)
}

export function subscribeBackend(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
