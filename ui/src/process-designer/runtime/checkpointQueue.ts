// Durable offline queue for process task-step checkpoints. Mirrors the proven picking offlineQueue
// pattern (IndexedDB with a localStorage fallback, a guarded background drainer that retries on
// `online` + on an interval + on demand, 5xx/thrown = transient retry vs 4xx = permanent FAILED).
//
// WHY a separate queue (and why we HOLD at the task rather than advancing optimistically): unlike a
// picking confirm — whose result the operator does not need to keep walking — a task step's OUTPUTS
// (e.g. the location slotting.putaway picked, a lookup result) may be read by a downstream screen's
// placeholder or a transition. Advancing past a not-yet-run task would render those screens against
// stale/empty data and could branch wrongly. So when a checkpoint POST fails for a NETWORK reason we
// enqueue it durably AND hold the instance at the task showing "pending sync"; the drainer posts it
// on reconnect and the flow resumes from the returned currentStep. Screen-only stretches still run
// fully offline (no server round-trip per screen — spec §9). A 4xx is a permanent application
// rejection (e.g. RBAC) surfaced to the operator.

import type { CheckpointResult } from '../model'

export interface QueuedCheckpoint {
  id: string
  instanceId: string
  stepId: string
  data: Record<string, unknown>
  enqueuedAt: number
  attempts: number
  failed?: boolean
  lastError?: string
}

const DB_NAME = 'owcs-process'
const STORE = 'checkpoint-queue'
const LS_KEY = 'owcs.process.checkpointQueue'
const RETRY_INTERVAL_MS = 15_000

type Listener = (items: QueuedCheckpoint[]) => void
const listeners = new Set<Listener>()
let cache: QueuedCheckpoint[] = []
let started = false
let draining = false

// A callback the runtime registers so a successful drain can merge the returned data + advance the
// live instance (the queue does the network; the runtime owns instance state).
type Applier = (instanceId: string, result: CheckpointResult) => void
let applier: Applier | null = null
export function setCheckpointApplier(fn: Applier | null): void {
  applier = fn
}

function newId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

let idbAvailable = typeof indexedDB !== 'undefined'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    // Version 2: the step-event queue (stepQueue.ts) shares this database in its own store, so both
    // queues open at the SAME version (opening at a lower version than the stored one throws
    // VersionError). We create the checkpoint store if missing and never drop the step store.
    const req = indexedDB.open(DB_NAME, 2)
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE)) db.createObjectStore(STORE, { keyPath: 'id' })
      if (!db.objectStoreNames.contains('step-queue')) db.createObjectStore('step-queue', { keyPath: 'id' })
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(req.error)
  })
}

async function readAll(): Promise<QueuedCheckpoint[]> {
  if (!idbAvailable) return readLs()
  try {
    const db = await openDb()
    return await new Promise<QueuedCheckpoint[]>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readonly')
      const req = tx.objectStore(STORE).getAll()
      req.onsuccess = () => resolve((req.result as QueuedCheckpoint[]).sort((a, b) => a.enqueuedAt - b.enqueuedAt))
      req.onerror = () => reject(req.error)
    })
  } catch {
    idbAvailable = false
    return readLs()
  }
}

async function putItem(item: QueuedCheckpoint): Promise<void> {
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

function readLs(): QueuedCheckpoint[] {
  try {
    const raw = localStorage.getItem(LS_KEY)
    return raw ? (JSON.parse(raw) as QueuedCheckpoint[]) : []
  } catch {
    return []
  }
}

function writeLs(items: QueuedCheckpoint[]): void {
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

export function pendingFor(instanceId: string): QueuedCheckpoint | undefined {
  return cache.find((c) => c.instanceId === instanceId && !c.failed)
}
export function failedFor(instanceId: string): QueuedCheckpoint | undefined {
  return cache.find((c) => c.instanceId === instanceId && c.failed)
}

/** Enqueue a checkpoint for later send. The runtime holds the instance at the task until it drains. */
export async function enqueueCheckpoint(input: {
  instanceId: string
  stepId: string
  data: Record<string, unknown>
}): Promise<void> {
  const item: QueuedCheckpoint = {
    id: newId(),
    instanceId: input.instanceId,
    stepId: input.stepId,
    data: input.data,
    enqueuedAt: Date.now(),
    attempts: 0,
  }
  await putItem(item)
  await refreshCache()
  void drainQueue()
}

async function sendCheckpoint(item: QueuedCheckpoint): Promise<CheckpointResult> {
  const res = await fetch(`/api/process-designer/instances/${encodeURIComponent(item.instanceId)}/checkpoint`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ stepId: item.stepId, data: item.data }),
  })
  if (!res.ok) {
    const body = await res.text().catch(() => '')
    const err = new Error(body || `${res.status} ${res.statusText}`) as Error & { httpStatus?: number }
    err.httpStatus = res.status
    throw err
  }
  return (await res.json()) as CheckpointResult
}

export async function drainQueue(): Promise<void> {
  if (draining) return
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return
  draining = true
  try {
    const items = (await readAll()).filter((c) => !c.failed)
    for (const item of items) {
      try {
        const result = await sendCheckpoint(item)
        await deleteItem(item.id)
        // Server is idempotent on instance+step; apply the result to the live instance (advance).
        applier?.(item.instanceId, result)
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

export async function retryFailed(id: string): Promise<void> {
  const item = cache.find((c) => c.id === id)
  if (!item) return
  await putItem({ ...item, failed: undefined, lastError: undefined })
  await refreshCache()
  void drainQueue()
}

let intervalId: number | undefined
export function startQueueDrainer(): void {
  if (started) return
  started = true
  void refreshCache()
  const onOnline = () => void drainQueue()
  window.addEventListener('online', onOnline)
  intervalId = window.setInterval(() => void drainQueue(), RETRY_INTERVAL_MS)
  void drainQueue()
}
