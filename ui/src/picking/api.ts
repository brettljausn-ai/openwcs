// Picking-execution API (the "execution layer" epic, item 2). The order-management service exposes a
// flat queue of pick tasks (one row per order line still owing units) and a per-line confirm. The
// guided console walks the operator through this queue, one "next pick" at a time.
//
// Wire contract (backend built in parallel):
//   GET  /api/orders/pick-tasks?warehouseId=  -> PickTask[]
//   POST /api/orders/pick-tasks/{lineId}/confirm { pickedQty, short? } -> PickTask (updated row)
//
// All requests use bare fetch('/api/...'); the global interceptor attaches the Bearer token.

export type PickTaskStatus = 'OPEN' | 'IN_PROGRESS' | 'PICKED' | 'SHORT' | string

export interface PickTask {
  orderId: string
  orderCode: string
  lineId: string
  lineNo: number
  skuId: string
  /** Source location to pick from. Null when allocation hasn't pinned a location yet. */
  locationId: string | null
  requestedQty: number
  pickedQty: number
  remainingQty: number
  status: PickTaskStatus
}

async function unwrap<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new Error(text || `${res.status} ${res.statusText}`)
  }
  return res.json() as Promise<T>
}

/** The flat pick-task queue for a warehouse (one row per still-owing order line). */
export async function listPickTasks(warehouseId: string): Promise<PickTask[]> {
  const body = await unwrap<unknown>(
    await fetch(`/api/orders/pick-tasks?warehouseId=${encodeURIComponent(warehouseId)}`),
  )
  // Tolerate either a bare array or a Spring page envelope.
  if (Array.isArray(body)) return body as PickTask[]
  const page = body as { content?: PickTask[] } | null
  return page?.content ?? []
}

/** Confirm a pick on a line: a full pick (pickedQty = remaining) or a short (short:true). Returns the
 *  updated row so the caller can reconcile it locally before the next queue refetch. */
export async function confirmPick(
  lineId: string,
  pickedQty: number,
  short?: boolean,
): Promise<PickTask> {
  return unwrap<PickTask>(
    await fetch(`/api/orders/pick-tasks/${encodeURIComponent(lineId)}/confirm`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pickedQty, short: short ?? false }),
    }),
  )
}
