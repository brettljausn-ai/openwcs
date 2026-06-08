// Shared id -> human-friendly code resolver for the operations screens. Fetches the master-data
// SKU catalog (global) plus the warehouse's locations and equipment once, and exposes lookups so
// screens can show codes (SKU code, location code, equipment code) instead of raw UUIDs.
import { useEffect, useState } from 'react'

interface SkuRef {
  id: string
  code: string
  description?: string | null
}
interface CodeRef {
  id: string
  code?: string | null
  name?: string | null
}

export interface Catalog {
  /** SKU code, or '—' when the id is unknown/empty. */
  skuCode: (id?: string | null) => string
  /** SKU code plus description, e.g. "SKU-1 — Widget"; falls back to the code or '—'. */
  skuLabel: (id?: string | null) => string
  /** Location code, or '—'. */
  locationCode: (id?: string | null) => string
  /** Equipment code (name in parentheses when present), or '—'. */
  equipmentLabel: (id?: string | null) => string
  loading: boolean
}

async function getJson(url: string): Promise<unknown> {
  const res = await fetch(url)
  if (!res.ok) return null
  return res.json().catch(() => null)
}

function content<T>(body: unknown): T[] {
  if (Array.isArray(body)) return body as T[]
  const page = body as { content?: T[] } | null
  return page?.content ?? []
}

/**
 * Resolve ids to codes for a warehouse. SKUs are global; locations and equipment are
 * warehouse-scoped. Pass the current warehouse id; the maps refresh when it changes.
 */
export function useCatalog(warehouseId?: string | null): Catalog {
  const [skus, setSkus] = useState<Map<string, SkuRef>>(new Map())
  const [locations, setLocations] = useState<Map<string, CodeRef>>(new Map())
  const [equipment, setEquipment] = useState<Map<string, CodeRef>>(new Map())
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    const wh = warehouseId ? encodeURIComponent(warehouseId) : ''
    Promise.all([
      getJson('/api/master-data/skus?size=1000'),
      warehouseId ? getJson(`/api/master-data/locations?warehouseId=${wh}&size=1000`) : Promise.resolve([]),
      warehouseId ? getJson(`/api/master-data/equipment?warehouseId=${wh}`) : Promise.resolve([]),
    ])
      .then(([skuBody, locBody, eqpBody]) => {
        if (cancelled) return
        setSkus(new Map(content<SkuRef>(skuBody).map((s) => [s.id, s])))
        setLocations(new Map(content<CodeRef>(locBody).map((l) => [l.id, l])))
        setEquipment(new Map(content<CodeRef>(eqpBody).map((e) => [e.id, e])))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [warehouseId])

  return {
    skuCode: (id) => (id ? skus.get(id)?.code ?? short(id) : '—'),
    skuLabel: (id) => {
      if (!id) return '—'
      const s = skus.get(id)
      if (!s) return short(id)
      return s.description ? `${s.code} — ${s.description}` : s.code
    },
    locationCode: (id) => (id ? locations.get(id)?.code ?? short(id) : '—'),
    equipmentLabel: (id) => {
      if (!id) return '—'
      const e = equipment.get(id)
      if (!e || !e.code) return short(id)
      return e.name ? `${e.code} — ${e.name}` : e.code
    },
    loading,
  }
}

/** Last-resort fallback for an id with no known code: a short, recognisable prefix. */
function short(id: string): string {
  return id.length > 10 ? `${id.slice(0, 8)}…` : id
}
