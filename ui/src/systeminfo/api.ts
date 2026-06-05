// System info API — version + health for every backend service, aggregated by the gateway
// (GET /api/system/services). The global authFetch interceptor attaches the Keycloak JWT.

export interface ServiceStatus {
  name: string
  // Runtime kind: 'java' (Spring Boot) or 'go' (device adapter).
  kind: string
  // Health: 'UP' | 'DOWN' | 'UNKNOWN' (Spring health status, or reachability for Go adapters).
  status: string
  version: string
  commit: string
  buildTime: string
  // Round-trip time of the gateway's probe, in milliseconds.
  latencyMs: number
}

export async function listServices(): Promise<ServiceStatus[]> {
  const res = await fetch('/api/system/services')
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as ServiceStatus[]
}

export interface ServiceLogs {
  service: string
  tail: number
  logs: string
  // Present when the Docker socket was unreachable or the container wasn't found.
  error?: string
}

// Recent container logs for a service, read by the gateway via the Docker socket.
export async function fetchServiceLogs(name: string, tail = 200): Promise<ServiceLogs> {
  const res = await fetch(`/api/system/services/${encodeURIComponent(name)}/logs?tail=${tail}`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as ServiceLogs
}
