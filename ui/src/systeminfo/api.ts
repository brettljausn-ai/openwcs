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
  // Present when the log file couldn't be read.
  error?: string
}

// The last `tail` lines of a service's daily log file for `date` (yyyy-MM-dd); most recent day when
// `date` is omitted. Logs are read by the gateway from the shared per-service log volume.
export async function fetchServiceLogs(name: string, tail = 200, date?: string): Promise<ServiceLogs> {
  const params = new URLSearchParams({ tail: String(tail) })
  if (date) params.set('date', date)
  const res = await fetch(`/api/system/services/${encodeURIComponent(name)}/logs?${params.toString()}`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as ServiceLogs
}

// The days (yyyy-MM-dd, newest first) for which a service has a log file — drives the day picker.
export async function fetchLogDays(name: string): Promise<string[]> {
  const res = await fetch(`/api/system/services/${encodeURIComponent(name)}/log-days`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as string[]
}
