// Durable offline confirm queue for RF picking. A handheld scanner on warehouse Wi-Fi loses the
// network constantly (between aisles, near steel racking). Picking must NOT block on the network:
// when a confirm POST fails for a NETWORK reason (offline, fetch threw, or a 5xx from the gateway),
// we enqueue the confirm durably and let the operator keep walking the queue. A background drainer
// retries on `online`, on an interval, and on demand.
//
// Storage: IndexedDB (object store keyed by an auto id), with a localStorage fallback for the rare
// browser/profile where IndexedDB is unavailable. Either way the queue survives a reload / SW
// auto-update mid-shift.
//
// 5xx vs 4xx: a 5xx (or thrown fetch / offline) is a transient transport failure -> keep retrying.
// A 4xx (e.g. 409 "not pickable any more", 404 line gone) is a permanent application rejection ->
// stop retrying, mark the item FAILED and surface it for operator attention (it would loop forever
// otherwise). 401 is handled by the global auth interceptor (it logs the operator out), so we treat
// it as failed here rather than retrying a doomed call.
//
// Idempotency assumption: the backend confirm (POST /api/orders/pick-tasks/{lineId}/confirm) is
// expected to tolerate a re-post of the same {pickedQty,short} for a line — i.e. confirming an
// already-confirmed line is a no-op (or returns the same closed row), not a double-decrement. This
// holds for the current order-service contract (the line transitions OPEN->PICKED/SHORT once and a
// repeat confirm on a closed line is rejected 409, which we treat as "already done" = drop). We
// therefore retry safely; a double-send (queued offline, then the original also succeeded on a
// flaky link) lands on a closed line and is dropped, not double-counted.

export interface QueuedConfirm {
  id: string
  lineId: string
  pickedQty: number
  short: boolean
  /** Human label captured at enqueue time so the operator can see WHAT is pending without a lookup. */
  label: string
  enqueuedAt: number
  attempts: number
  /** Set when a 4xx (or auth) permanently rejected this item; it stops retrying and needs attention. */
  failed?: boolean
  lastError?: string
}

const DB_NAME = 'owcs-picking'
const STORE = 'confirm-queue'
const LS_KEY = 'owcs.picking.confirmQueue'
const RETRY_INTERVAL_MS = 15_000

type Listener = (items: QueuedConfirm[]) => void
const listeners = new Set<Listener>()
let cache: QueuedConfirm[] = []
let started = false
let draining = false

function newId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

// --- Storage: IndexedDB with a localStorage fallback ---------------------------------------------

let idbAvailable = typeof indexedDB !== 'undefined'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'id' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function readAll(): Promise<QueuedConfirm[]> {
  if (!idbAvailable) return readLs()
  try {
    const db = await openDb()
    return await new Promise<QueuedConfirm[]>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readonly')
      const req = tx.objectStore(STORE).getAll()
      req.onsuccess = () => resolve((req.result as QueuedConfirm[]).sort((a, b) => a.enqueuedAt - b.enqueuedAt))
      req.onerror = () => reject(req.error)
    })
  } catch {
    idbAvailable = false
    return readLs()
  }
}

async function putItem(item: QueuedConfirm): Promise<void> {
  if (!idbAvailable) return writeLs([...cache.filter((c) => c.id !== item.id), item])
  try {
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite')
      tx.objectStore(STORE).put(item)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    idbAvailable = false
    writeLs([...cache.filter((c) => c.id !== item.id), item])
  }
}

async function deleteItem(id: string): Promise<void> {
  if (!idbAvailable) return writeLs(cache.filter((c) => c.id !== id))
  try {
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite')
      tx.objectStore(STORE).delete(id)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
  } catch {
    idbAvailable = false
    writeLs(cache.filter((c) => c.id !== id))
  }
}

function readLs(): QueuedConfirm[] {
  try {
    const raw = localStorage.getItem(LS_KEY)
    return raw ? (JSON.parse(raw) as QueuedConfirm[]) : []
  } catch {
    return []
  }
}

function writeLs(items: QueuedConfirm[]): void {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(items))
  } catch {
    /* storage full / disabled — best effort */
  }
}

// --- Cache + subscription -------------------------------------------------------------------------

function emit(): void {
  for (const l of listeners) l(cache)
}

async function refreshCache(): Promise<void> {
  cache = await readAll()
  emit()
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener)
  listener(cache)
  return () => listeners.delete(listener)
}

export function getQueue(): QueuedConfirm[] {
  return cache
}

/** Pending = not permanently failed (those still need a human, but aren't "in flight to retry"). */
export function pendingCount(): number {
  return cache.filter((c) => !c.failed).length
}

export function failedCount(): number {
  return cache.filter((c) => c.failed).length
}

// --- Enqueue + drain ------------------------------------------------------------------------------

export async function enqueueConfirm(input: {
  lineId: string
  pickedQty: number
  short: boolean
  label: string
}): Promise<void> {
  const item: QueuedConfirm = {
    id: newId(),
    lineId: input.lineId,
    pickedQty: input.pickedQty,
    short: input.short,
    label: input.label,
    enqueuedAt: Date.now(),
    attempts: 0,
  }
  await putItem(item)
  await refreshCache()
  // Try right away in case the network is actually back already.
  void drainQueue()
}

// The actual network send. Imported lazily to avoid a cycle (api.ts -> nothing, but keep it clean).
async function sendConfirm(item: QueuedConfirm): Promise<void> {
  const res = await fetch(`/api/orders/pick-tasks/${encodeURIComponent(item.lineId)}/confirm`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pickedQty: item.pickedQty, short: item.short }),
  })
  if (res.ok) return
  // 409 on a closed line = the line is already confirmed (idempotent re-post / it was completed
  // elsewhere). Treat as success and drop it rather than parking it as a failure.
  if (res.status === 409) return
  const body = await res.text().catch(() => '')
  const err = new Error(body || `${res.status} ${res.statusText}`) as Error & { httpStatus?: number }
  err.httpStatus = res.status
  throw err
}

/** Drain the queue once. Safe to call concurrently (guarded). Returns when this pass finishes. */
export async function drainQueue(): Promise<void> {
  if (draining) return
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return
  draining = true
  try {
    // Re-read so we drain anything enqueued in another tab too.
    const items = (await readAll()).filter((c) => !c.failed)
    for (const item of items) {
      try {
        await sendConfirm(item)
        await deleteItem(item.id)
      } catch (e) {
        const status = (e as { httpStatus?: number }).httpStatus
        const msg = e instanceof Error ? e.message : String(e)
        // 4xx (incl. 401) = permanent application rejection: stop retrying, mark FAILED for a human.
        // 5xx / thrown fetch (no status) / offline = transient: bump attempts, keep it for retry.
        const permanent = status !== undefined && status >= 400 && status < 500
        const updated: QueuedConfirm = {
          ...item,
          attempts: item.attempts + 1,
          lastError: msg,
          failed: permanent || undefined,
        }
        await putItem(updated)
        // A thrown fetch / network drop mid-pass: stop the pass, the next online/interval tick retries.
        if (status === undefined) break
      }
    }
  } finally {
    draining = false
    await refreshCache()
  }
}

/** Forget a permanently-failed item (operator acknowledged it / will handle the pick manually). */
export async function dismissFailed(id: string): Promise<void> {
  await deleteItem(id)
  await refreshCache()
}

/** Re-arm a failed item for retry (operator believes the transient condition is gone). */
export async function retryFailed(id: string): Promise<void> {
  const item = cache.find((c) => c.id === id)
  if (!item) return
  await putItem({ ...item, failed: undefined, lastError: undefined })
  await refreshCache()
  void drainQueue()
}

let intervalId: number | undefined

/** Start the background drainer once (idempotent). Call from the picking screen mount. */
export function startQueueDrainer(): void {
  if (started) return
  started = true
  void refreshCache()
  const onOnline = () => void drainQueue()
  window.addEventListener('online', onOnline)
  intervalId = window.setInterval(() => void drainQueue(), RETRY_INTERVAL_MS)
  // Drain immediately too, in case there are leftovers from a previous session.
  void drainQueue()
}
