// Client for the Warehouse access admin screen. Warehouse names come from master-data; the
// per-user allowed set + default come from IAM. All calls go through the gateway and rely on the
// global Bearer interceptor (ui/src/lib/authFetch.ts).

export interface Warehouse {
  id: string
  code: string
  name: string
  status?: string
}

// One user's warehouse access — matches IAM's WarehouseAccessView { warehouses, defaultWarehouse }.
export interface WarehouseAccess {
  warehouses: string[]
  defaultWarehouse: string | null
}

interface PageResponse<T> {
  content: T[]
}

async function ok<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

export async function listWarehouses(): Promise<Warehouse[]> {
  const page = await ok<PageResponse<Warehouse>>(await fetch('/api/master-data/warehouses?size=200'))
  return (page.content ?? []).filter((w) => w.status !== 'ARCHIVED')
}

/** Every user's warehouse access, keyed by username (only users with a mapping appear). */
export async function getAllAccess(): Promise<Record<string, WarehouseAccess>> {
  return ok(await fetch('/api/iam/warehouse-access'))
}

/** Replace a user's allowed warehouses + default. Default must be one of the allowed warehouses. */
export async function setAccess(username: string, access: WarehouseAccess): Promise<WarehouseAccess> {
  return ok(
    await fetch(`/api/iam/warehouse-access/${encodeURIComponent(username)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(access),
    }),
  )
}
