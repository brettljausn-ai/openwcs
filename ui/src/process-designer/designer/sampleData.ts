// Sample/default values for the data object so the designer's live preview + simulate can resolve
// {{placeholders}} to something readable without a running instance. Domain types get a plausible
// code (NOT a UUID — the preview catalog is the identity passthrough below).

import type { Catalog } from '../../lib/useCatalog'
import type { DataVar } from '../model'

export function sampleDataFor(schema: DataVar[]): Record<string, unknown> {
  const data: Record<string, unknown> = {}
  for (const v of schema) {
    switch (v.type) {
      case 'string': data[v.name] = `${v.name.toUpperCase()}-1`; break
      case 'number': data[v.name] = 5; break
      case 'boolean': data[v.name] = true; break
      case 'date': data[v.name] = new Date().toISOString().slice(0, 10); break
      case 'sku': data[v.name] = 'SKU-1'; break
      case 'location': data[v.name] = 'A-01-01'; break
      case 'hu': data[v.name] = 'HU-1001'; break
    }
  }
  return data
}

// A catalog stand-in for the preview: domain values are already sample codes, so resolvers pass them
// through (skuLabel adds a fake description so {{sku.name}} shows something).
export const PREVIEW_CATALOG: Pick<Catalog, 'skuCode' | 'skuLabel' | 'locationCode' | 'equipmentLabel'> = {
  skuCode: (id) => (id ? String(id) : '—'),
  skuLabel: (id) => (id ? `${id} — Sample item` : '—'),
  locationCode: (id) => (id ? String(id) : '—'),
  equipmentLabel: (id) => (id ? String(id) : '—'),
}
