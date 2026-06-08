// Client for the master-data service (via the gateway at /api/master-data/**).
// Types mirror the JPA domain classes in services/master-data/.../domain/*.

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface Warehouse {
  id?: string
  code: string
  name: string
  timezone: string
  status: string
  addressLine1?: string
  addressLine2?: string
  city?: string
  region?: string
  postalCode?: string
  country?: string
}

export interface Sku {
  id?: string
  code: string
  description?: string
  status: string
  ownerClient?: string
  batchTracked: boolean
  serialTracked: boolean
  dateTracked: boolean
}

// SKU sub-resources. SKU, UoM and barcodes are host-owned master data — the WCS reads
// them only (managed via host sync); there are deliberately no create/edit/delete calls.
export interface UnitOfMeasure {
  id?: string
  skuId?: string
  code: string
  parentUomId?: string | null
  qtyInParent?: number | null
  lengthMm?: number | null
  widthMm?: number | null
  heightMm?: number | null
  weightG?: number | null
  baseUnit: boolean
}

export interface Barcode {
  id?: string
  skuId?: string
  uomId?: string | null
  value: string
  barcodeTypeId?: string | null
}

export interface StorageBlock {
  id?: string
  warehouseId: string
  code: string
  storageType: string
  slottingGranularity: string
  equipmentId?: string | null
  gtp: boolean
  allowedHuTypes?: string[] | null
  status: string
}

export interface Location {
  id?: string
  warehouseId: string
  code: string
  locationType: string
  purpose: string
  parentId?: string | null
  equipmentId?: string | null
  blockId?: string | null
  status: string
  mixedAllowed: boolean
  laneDepth: number
  replenishmentClass?: string | null
  aisle?: string | null
  rackLevel?: number | null
  distanceToExit?: number | null
  side?: string | null
  posX?: number | null
  posY?: number | null
  posZ?: number | null
  hardwareAddress?: string | null
}

export interface Equipment {
  id?: string
  warehouseId: string
  family: string
  type?: string | null
  subtype?: string | null
  defaultWidthM?: number | null
  defaultHeightM?: number | null
  defaultLengthM?: number | null
  processTypes?: string[] | null
  vendor?: string | null
  model?: string | null
  adapterEndpoint?: string | null
  status: string
}

export interface LabelTemplate {
  id?: string
  code: string
  name?: string | null
  widthMm: number
  heightMm: number
  dpi: number
  elements: unknown[]
  status: string
}

export interface HandlingUnitType {
  id?: string
  name: string
  lengthMm?: number
  widthMm?: number
  heightMm?: number
  weightLimitG?: number
  nestable: boolean
  compartments: number
  storableInAutomation: boolean
  transportableOnConveyor: boolean
  shipper: boolean
  status?: 'ACTIVE' | 'ARCHIVED'
}

const json = { 'Content-Type': 'application/json' }

async function unwrap<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      const msg = body?.detail || body?.message || body?.error || body?.title
      if (msg) detail = msg
    } catch {
      /* no JSON body */
    }
    throw new Error(detail)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

async function expectOk(res: Response): Promise<void> {
  if (!res.ok) {
    let detail = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      const msg = body?.detail || body?.message || body?.error || body?.title
      if (msg) detail = msg
    } catch {
      /* ignore */
    }
    throw new Error(detail)
  }
}

const base = '/api/master-data'

// ---------------------------------------------------------------- Warehouses
export async function listWarehouses(): Promise<Warehouse[]> {
  const p = await unwrap<Page<Warehouse>>(await fetch(`${base}/warehouses?size=500`))
  return p.content
}
export async function createWarehouse(w: Warehouse): Promise<Warehouse> {
  return unwrap(await fetch(`${base}/warehouses`, { method: 'POST', headers: json, body: JSON.stringify(w) }))
}
export async function updateWarehouse(id: string, w: Warehouse): Promise<Warehouse> {
  return unwrap(await fetch(`${base}/warehouses/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(w) }))
}
export async function deleteWarehouse(id: string): Promise<void> {
  return expectOk(await fetch(`${base}/warehouses/${id}`, { method: 'DELETE' }))
}

// ---------------------------------------------------------------------- SKUs
// Host-owned master data: read-only in the WCS (no create/update/delete — those live on the host).
export async function listSkus(q?: string): Promise<Sku[]> {
  const url = q ? `${base}/skus?size=500&q=${encodeURIComponent(q)}` : `${base}/skus?size=500`
  const p = await unwrap<Page<Sku>>(await fetch(url))
  return p.content
}
export async function listSkuUoms(skuId: string): Promise<UnitOfMeasure[]> {
  return unwrap(await fetch(`${base}/skus/${skuId}/uoms`))
}
export async function listSkuBarcodes(skuId: string): Promise<Barcode[]> {
  return unwrap(await fetch(`${base}/skus/${skuId}/barcodes`))
}

// -------------------------------------------------------------- Storage blocks
export async function listStorageBlocks(warehouseId: string): Promise<StorageBlock[]> {
  return unwrap(await fetch(`${base}/storage-blocks?warehouseId=${encodeURIComponent(warehouseId)}`))
}
export async function createStorageBlock(b: StorageBlock): Promise<StorageBlock> {
  return unwrap(await fetch(`${base}/storage-blocks`, { method: 'POST', headers: json, body: JSON.stringify(b) }))
}
export async function updateStorageBlock(id: string, b: StorageBlock): Promise<StorageBlock> {
  return unwrap(await fetch(`${base}/storage-blocks/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(b) }))
}
export async function archiveStorageBlock(id: string): Promise<StorageBlock> {
  return unwrap(await fetch(`${base}/storage-blocks/${id}/archive`, { method: 'PUT' }))
}
export async function restoreStorageBlock(id: string): Promise<StorageBlock> {
  return unwrap(await fetch(`${base}/storage-blocks/${id}/restore`, { method: 'PUT' }))
}
export async function deleteStorageBlock(id: string): Promise<void> {
  return expectOk(await fetch(`${base}/storage-blocks/${id}`, { method: 'DELETE' }))
}
// IDs of the locations belonging to a block — used to gate deletion (must hold no stock/HUs).
// Tolerates either a plain array or a paged {content:[…]} response shape.
export async function listBlockLocationIds(warehouseId: string, blockId: string): Promise<string[]> {
  const res = await unwrap<Location[] | Page<Location>>(
    await fetch(
      `${base}/locations?warehouseId=${encodeURIComponent(warehouseId)}&blockId=${encodeURIComponent(blockId)}&size=1000`,
    ),
  )
  const list = Array.isArray(res) ? res : res.content
  return list.map((l) => l.id).filter((id): id is string => !!id)
}

// ------------------------------------------------------------------ Locations
export async function listLocations(warehouseId: string): Promise<Location[]> {
  const p = await unwrap<Page<Location>>(
    await fetch(`${base}/locations?size=500&warehouseId=${encodeURIComponent(warehouseId)}`),
  )
  return p.content
}
export async function createLocation(l: Location): Promise<Location> {
  return unwrap(await fetch(`${base}/locations`, { method: 'POST', headers: json, body: JSON.stringify(l) }))
}
export async function bulkCreateLocations(locations: Location[]): Promise<Location[]> {
  return unwrap(
    await fetch(`${base}/locations/bulk`, { method: 'POST', headers: json, body: JSON.stringify(locations) }),
  )
}
export async function updateLocation(id: string, l: Location): Promise<Location> {
  return unwrap(await fetch(`${base}/locations/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(l) }))
}
export async function deleteLocation(id: string): Promise<void> {
  return expectOk(await fetch(`${base}/locations/${id}`, { method: 'DELETE' }))
}

// ------------------------------------------------------------------ Equipment
export async function listEquipment(warehouseId: string): Promise<Equipment[]> {
  return unwrap(await fetch(`${base}/equipment?warehouseId=${encodeURIComponent(warehouseId)}`))
}
export async function createEquipment(e: Equipment): Promise<Equipment> {
  return unwrap(await fetch(`${base}/equipment`, { method: 'POST', headers: json, body: JSON.stringify(e) }))
}
export async function updateEquipment(id: string, e: Equipment): Promise<Equipment> {
  return unwrap(await fetch(`${base}/equipment/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(e) }))
}

// ------------------------------------------------------------- Label templates
export async function listLabelTemplates(): Promise<LabelTemplate[]> {
  return unwrap(await fetch(`${base}/label-templates`))
}
export async function createLabelTemplate(t: LabelTemplate): Promise<LabelTemplate> {
  return unwrap(await fetch(`${base}/label-templates`, { method: 'POST', headers: json, body: JSON.stringify(t) }))
}
export async function updateLabelTemplate(id: string, t: LabelTemplate): Promise<LabelTemplate> {
  return unwrap(await fetch(`${base}/label-templates/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(t) }))
}
export async function deleteLabelTemplate(id: string): Promise<void> {
  return expectOk(await fetch(`${base}/label-templates/${id}`, { method: 'DELETE' }))
}

// -------------------------------------------------------- Handling unit types
export async function listHandlingUnitTypes(): Promise<HandlingUnitType[]> {
  return unwrap(await fetch(`${base}/handling-unit-types`))
}
export async function createHandlingUnitType(h: HandlingUnitType): Promise<HandlingUnitType> {
  return unwrap(await fetch(`${base}/handling-unit-types`, { method: 'POST', headers: json, body: JSON.stringify(h) }))
}
export async function updateHandlingUnitType(id: string, h: HandlingUnitType): Promise<HandlingUnitType> {
  return unwrap(await fetch(`${base}/handling-unit-types/${id}`, { method: 'PUT', headers: json, body: JSON.stringify(h) }))
}
export async function archiveHandlingUnitType(id: string): Promise<void> {
  return expectOk(await fetch(`${base}/handling-unit-types/${id}/archive`, { method: 'PUT' }))
}
export async function restoreHandlingUnitType(id: string): Promise<void> {
  return expectOk(await fetch(`${base}/handling-unit-types/${id}/restore`, { method: 'PUT' }))
}
