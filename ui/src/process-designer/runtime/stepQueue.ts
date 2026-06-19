// Durable offline queue for process STEP EVENTS (every screen advance). Mirrors checkpointQueue.ts
// exactly (IndexedDB with a localStorage fallback, a guarded background drainer that retries on
// `online` + on an interval + on demand). It lives in the SAME IndexedDB database (owcs-process) as
// the checkpoint queue, in its own object store, so both survive a refresh / device swap / re-login.
//
// WHY a SEPARATE event from a checkpoint: a checkpoint RUNS server-side work for a task step and the
// runtime HOLDS until it drains (downstream screens read its outputs). A step event only RECORDS that
// the operator advanced past a screen, so the runtime is offline-first: it enqueues the event and
// advances IMMEDIATELY (it never blocks the operator). The server stamps the exact current_step + data
// from the latest event, which is what makes a refresh / new device / re-login resume EXACTLY here.
//
// ORDER + IDEMPOTENCY: each event carries a per-instance monotonic `seq`; the server dedups on
// (instanceId, seq). We drain oldest-first, so even if a later event lands first on a retry the server
// stays consistent. A 4xx is a permanent rejection (marked failed); a 5xx / network error is a
// transient retry.

const DB_NAME = 'owcs-process'
const STORE = 'step-queue'
const LS_KEY = 'owcs.process.stepQueue'
const RETRY_INTERVAL_MS = 15_000

export interface QueuedStep {
  id: string
  instanceId: string
  seq: number
  stepId: string
  stepType: string
  data: Record<string, unknown>
  enqueuedAt: number
  attempts: number
  failed?: boolean
  lastError?: string
}

type Listener = (items: QueuedStep[]) => void
const listeners = new Set<Listener>()
let cache: QueuedStep[] = []
let started = false
let draining = false

function newId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

let idbAvailable = typeof indexedDB !== 'undefined'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    // Version 2: add the step-queue store alongside the existing checkpoint-queue store. Opening at a
    // higher version triggers onupgradeneeded; we create our store only if missing and never drop the
    // checkpoint store, so the two queues coexist.
    const req = indexedDB.open(DB_NAME, 2)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains('checkpoint-queue')) db.createObjectStore('checkpoint-queue', { keyPath: 'id' })
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'id' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function readAll(): Promise<QueuedStep[]> {
  if (!idbAvailable) return readLs()
  try {
    const db = await openDb()
    return await new Promise<QueuedStep[]>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readonly')
      const req = tx.objectStore(STORE).getAll()
      // Oldest-first by enqueue time, then by seq, so events drain in the order they happened.
      req.onsuccess = () =>
        resolve((req.result as QueuedStep[]).sort((a, b) => a.enqueuedAt - b.enqueuedAt || a.seq - b.seq))
      req.onerror = () => reject(req.error)
    })
  } catch {
    idbAvailable = false
    return readLs()
  }
}

async function putItem(item: QueuedStep): Promise<void> {
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

function readLs(): QueuedStep[] {
  try {
    const raw = localStorage.getItem(LS_KEY)
    const items = raw ? (JSON.parse(raw) as QueuedStep[]) : []
    return items.sort((a, b) => a.enqueuedAt - b.enqueuedAt || a.seq - b.seq)
  } catch {
    return []
  }
}

function writeLs(items: QueuedStep[]): void {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(items))
  } catch {
    /* best effort */
  }
}

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

export function pendingStepsFor(instanceId: string): QueuedStep[] {
  return cache.filter((c) => c.instanceId === instanceId)
}

/** Enqueue a step event for later send and kick the drainer. Offline-first: the caller advances the
 *  instance immediately; this never blocks. Returns once persisted to IndexedDB / localStorage. */
export async function enqueueStep(input: {
  instanceId: string
  seq: number
  stepId: string
  stepType: string
  data: Record<string, unknown>
}): Promise<void> {
  const item: QueuedStep = {
    id: newId(),
    instanceId: input.instanceId,
    seq: input.seq,
    stepId: input.stepId,
    stepType: input.stepType,
    data: input.data,
    enqueuedAt: Date.now(),
    attempts: 0,
  }
  await putItem(item)
  await refreshCache()
  void drainQueue()
}

async function sendStep(item: QueuedStep): Promise<void> {
  const res = await fetch(`/api/process-designer/instances/${encodeURIComponent(item.instanceId)}/step`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ seq: item.seq, stepId: item.stepId, stepType: item.stepType, data: item.data }),
  })
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    const err = new Error(body || `${res.status} ${res.statusText}`) as Error & { httpStatus?: number }
    err.httpStatus = res.status
    throw err
  }
}

/** Drain queued step events oldest-first. The server is idempotent on (instanceId, seq), so a retry
 *  of an already-applied event is a clean no-op. Resolves once a pass completes (used on mount/resume
 *  to flush before reading the server's exact current_step). */
export async function drainQueue(): Promise<void> {
  if (draining) return
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return
  draining = true
  try {
    const items = (await readAll()).filter((c) => !c.failed)
    for (const item of items) {
      try {
        await sendStep(item)
        await deleteItem(item.id)
      } catch (e) {
        const status = (e as { httpStatus?: number }).httpStatus
        const msg = e instanceof Error ? e.message : String(e)
        const permanent = status !== undefined && status >= 400 && status < 500
        await putItem({ ...item, attempts: item.attempts + 1, lastError: msg, failed: permanent || undefined })
        if (status === undefined) break // network drop mid-pass: stop, retry next tick
      }
    }
  } finally {
    draining = false
    await refreshCache()
  }
}

/** Drain and RESOLVE only once the queue is empty (or only failed items remain). Used on resume so we
 *  flush every pending advance BEFORE reading the server's current_step (otherwise we could resume on
 *  a stale step). Bounded so it never hangs the screen when offline / a permanent failure sticks. */
export async function flushStepsForInstance(instanceId: string, maxWaitMs = 4000): Promise<void> {
  const deadline = Date.now() + maxWaitMs
  // Nothing queued for this instance: done.
  if (cache.filter((c) => c.instanceId === instanceId && !c.failed).length === 0) {
    await drainQueue()
  }
  while (Date.now() < deadline) {
    await drainQueue()
    const remaining = (await readAll()).filter((c) => c.instanceId === instanceId && !c.failed)
    if (remaining.length === 0) return
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return // offline: don't spin
    await new Promise((r) => setTimeout(r, 300))
  }
}

export async function retryFailed(id: string): Promise<void> {
  const item = cache.find((c) => c.id === id)
  if (!item) return
  await putItem({ ...item, failed: undefined, lastError: undefined })
  await refreshCache()
  void drainQueue()
}

export function startStepDrainer(): void {
  if (started) return
  started = true
  void refreshCache()
  const onOnline = () => void drainQueue()
  window.addEventListener('online', onOnline)
  window.setInterval(() => void drainQueue(), RETRY_INTERVAL_MS)
  void drainQueue()
}
