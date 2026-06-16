// {{placeholder}} resolution (spec §5). Header/detail text uses {{var}}, and {{var.code}} /
// {{var.name}} for domain-typed vars (sku/location/hu) which resolve via useCatalog to a code or
// label (never a UUID). Resolution is over the WHITELISTED dataSchema fields only — no expression
// evaluation. An unknown placeholder renders as a visible empty marker (spec: "not an error").

import type { Catalog } from '../lib/useCatalog'
import type { DataVar, VarType } from './model'

/** Visible marker for an unknown / empty placeholder (so a designer can see something is missing). */
export const EMPTY_MARKER = '—'

interface ResolveCtx {
  data: Record<string, unknown>
  schema: DataVar[]
  catalog?: Pick<Catalog, 'skuCode' | 'skuLabel' | 'locationCode' | 'equipmentLabel'>
}

function varType(name: string, schema: DataVar[]): VarType | undefined {
  return schema.find((v) => v.name === name)?.type
}

/** Resolve a single token like `sku`, `sku.code`, `sku.name`. */
function resolveToken(token: string, ctx: ResolveCtx): string {
  const [name, suffix] = token.split('.', 2)
  const declared = ctx.schema.some((v) => v.name === name)
  if (!declared) return EMPTY_MARKER // not whitelisted -> visible empty marker
  const raw = ctx.data[name]
  if (raw == null || raw === '') return EMPTY_MARKER

  const type = varType(name, ctx.schema)
  const cat = ctx.catalog

  // Domain types resolve through the catalog to a code/label, never a UUID.
  if (type === 'sku' && cat) {
    const id = String(raw)
    if (suffix === 'name') {
      const label = cat.skuLabel(id)
      // skuLabel = "CODE — desc"; the name part is after the em-dash, else the label itself
      const dash = label.indexOf(' — ')
      return dash >= 0 ? label.slice(dash + 3) : label
    }
    return suffix === 'code' || !suffix ? cat.skuCode(id) : cat.skuLabel(id)
  }
  if (type === 'location' && cat) {
    return cat.locationCode(String(raw))
  }
  if (type === 'hu' && cat) {
    // No HU resolver in the shared catalog yet; show the raw code (handheld HU codes are operator-
    // readable already). Degrades to the value, never a marker, so the operator still sees the HU.
    return String(raw)
  }
  // Primitives: booleans as Yes/No, dates/strings/numbers verbatim.
  if (type === 'boolean') return raw ? 'Yes' : 'No'
  return String(raw)
}

/** Replace every {{token}} in `text`. HTML is never injected — React renders the returned string as
 *  a text node, so escaping is inherent. */
export function resolvePlaceholders(text: string | undefined, ctx: ResolveCtx): string {
  if (!text) return ''
  return text.replace(/\{\{\s*([A-Za-z0-9_.]+)\s*\}\}/g, (_m, token: string) => resolveToken(token, ctx))
}

/** Extract the variable base-names referenced by {{...}} in a text (for validation: unknown refs). */
export function placeholderRefs(text: string | undefined): string[] {
  if (!text) return []
  const out: string[] = []
  const re = /\{\{\s*([A-Za-z0-9_.]+)\s*\}\}/g
  let m: RegExpExecArray | null
  while ((m = re.exec(text))) out.push(m[1].split('.', 1)[0])
  return out
}
