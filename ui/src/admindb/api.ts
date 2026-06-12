// Admin database console API (master-data: /api/master-data/admin/db). ADMIN-only, read-only:
// the backend accepts a single SELECT / WITH…SELECT, runs it in a READ ONLY transaction with a
// statement timeout, and caps the rows returned. The global authFetch interceptor attaches the
// Keycloak JWT; the gateway forwards the roles the backend checks.

export interface ColumnMeta {
  name: string
  type: string
}

export interface TableMeta {
  name: string
  columns: ColumnMeta[]
}

export interface SchemaMeta {
  name: string
  tables: TableMeta[]
}

// One result cell: the backend serializes values as string/number/boolean/null
// (timestamps as ISO strings, exotic types as their PostgreSQL text form).
export type CellValue = string | number | boolean | null

export interface QueryResult {
  columns: ColumnMeta[]
  rows: CellValue[][]
  rowCount: number
  truncated: boolean
  executionMs: number
}

// Pull the human-readable detail out of an RFC 9457 problem response (the backend surfaces the
// PostgreSQL error message there so the admin can fix the query).
async function errorOf(res: Response): Promise<string> {
  try {
    const body = await res.json()
    if (typeof body?.detail === 'string' && body.detail) return body.detail
    if (typeof body?.message === 'string' && body.message) return body.message
  } catch {
    // not JSON — fall through to the status line
  }
  return `${res.status} ${res.statusText}`
}

/** All non-system schemas with their tables and column metadata. */
export async function fetchSchemas(): Promise<SchemaMeta[]> {
  const res = await fetch('/api/master-data/admin/db/schemas')
  if (!res.ok) throw new Error(await errorOf(res))
  return (await res.json()) as SchemaMeta[]
}

/** Execute one read-only SELECT; `maxRows` caps the result (default 200, max 1000). */
export async function runQuery(sql: string, maxRows?: number): Promise<QueryResult> {
  const res = await fetch('/api/master-data/admin/db/query', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(maxRows == null ? { sql } : { sql, maxRows }),
  })
  if (!res.ok) throw new Error(await errorOf(res))
  return (await res.json()) as QueryResult
}
