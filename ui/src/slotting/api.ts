// Client for the slotting service (via the gateway at /api/slotting) + master-data storage blocks.

export interface PickSlot {
  id?: string
  warehouseId: string
  locationId: string
  skuId: string
  uomId: string
  minQty: number
  maxQty: number
  directToPick: boolean
  status?: string
}

export interface StorageProfile {
  id?: string
  warehouseId: string
  skuId: string
  blockId: string
  velocityClass: string // A | B | C
  consolidate: boolean
  minAisles: number
  maxAislePct: number
  status?: string
}

export interface StorageBlock {
  id: string
  code: string
  storageType: string
  slottingGranularity: string
  gtp: boolean
}

async function ok<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`)
  return (await res.json()) as T
}

const json = { 'Content-Type': 'application/json' }

// ---- pick faces (manual slotting) ----
export async function listPickSlots(warehouseId: string): Promise<PickSlot[]> {
  return ok(await fetch(`/api/slotting/pick-slots?warehouseId=${encodeURIComponent(warehouseId)}`))
}

export async function createPickSlot(slot: PickSlot): Promise<PickSlot> {
  return ok(await fetch('/api/slotting/pick-slots', { method: 'POST', headers: json, body: JSON.stringify(slot) }))
}

export async function deletePickSlot(id: string): Promise<void> {
  const res = await fetch(`/api/slotting/pick-slots/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`${res.status}`)
}

// ---- block slotting (automated pools) ----
export async function listStorageProfiles(warehouseId: string): Promise<StorageProfile[]> {
  return ok(await fetch(`/api/slotting/storage-profiles?warehouseId=${encodeURIComponent(warehouseId)}`))
}

export async function createStorageProfile(p: StorageProfile): Promise<StorageProfile> {
  return ok(await fetch('/api/slotting/storage-profiles', { method: 'POST', headers: json, body: JSON.stringify(p) }))
}

export async function deleteStorageProfile(id: string): Promise<void> {
  const res = await fetch(`/api/slotting/storage-profiles/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`${res.status}`)
}

export async function listStorageBlocks(warehouseId: string): Promise<StorageBlock[]> {
  return ok(await fetch(`/api/master-data/storage-blocks?warehouseId=${encodeURIComponent(warehouseId)}`))
}
