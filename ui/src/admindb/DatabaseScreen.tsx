import { KeyboardEvent, useCallback, useEffect, useMemo, useState } from 'react'
import { useT } from '../i18n/useT'
import DataTable, { Column } from '../ui/DataTable'
import { CellValue, QueryResult, SchemaMeta, fetchSchemas, runQuery } from './api'

// Administration → Database: a read-only SQL console for admins. Left: schema → table tree
// (clicking a table writes and runs `select * from <schema>.<table> limit 100`). Main: a
// monospace SQL editor (Cmd/Ctrl+Enter runs) and the result grid. The backend only accepts a
// single SELECT and executes it in a READ ONLY transaction with a timeout and row cap, so
// nothing on this screen can modify data.

const SQL_STORAGE_KEY = 'openwcs.admindb.sql'
const DEFAULT_SQL = 'select * from master_data.warehouse limit 100'

function loadLastSql(): string {
  try {
    return localStorage.getItem(SQL_STORAGE_KEY) || DEFAULT_SQL
  } catch {
    return DEFAULT_SQL
  }
}

// Render one result cell: NULL dimmed, booleans/numbers as text, long strings as-is (the cell
// scrolls horizontally with the table).
function CellView({ value }: { value: CellValue }) {
  if (value === null) return <span style={{ color: 'var(--text-dim)', fontStyle: 'italic' }}>NULL</span>
  return <>{String(value)}</>
}

export default function DatabaseScreen() {
  const t = useT('admindb')
  const [schemas, setSchemas] = useState<SchemaMeta[]>([])
  const [schemasError, setSchemasError] = useState<string | null>(null)
  const [openSchemas, setOpenSchemas] = useState<Set<string>>(new Set())
  const [selectedTable, setSelectedTable] = useState<string | null>(null) // "schema.table"

  const [sql, setSql] = useState(loadLastSql)
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<QueryResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchSchemas()
      .then((data) => {
        setSchemas(data)
        // Open the first schema that has tables so the tree is immediately useful.
        const first = data.find((s) => s.tables.length > 0)
        if (first) setOpenSchemas(new Set([first.name]))
      })
      .catch((e) => setSchemasError(e instanceof Error ? e.message : String(e)))
  }, [])

  // Remember the last query so the console reopens where the admin left off.
  useEffect(() => {
    try {
      localStorage.setItem(SQL_STORAGE_KEY, sql)
    } catch {
      // storage full/blocked — losing the draft is acceptable
    }
  }, [sql])

  const execute = useCallback(async (statement: string) => {
    if (!statement.trim()) return
    setRunning(true)
    setError(null)
    try {
      setResult(await runQuery(statement))
    } catch (e) {
      setResult(null)
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setRunning(false)
    }
  }, [])

  function toggleSchema(name: string) {
    setOpenSchemas((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  function openTable(schema: string, table: string) {
    const statement = `select * from ${schema}.${table} limit 100`
    setSelectedTable(`${schema}.${table}`)
    setSql(statement)
    void execute(statement)
  }

  function onEditorKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault()
      void execute(sql)
    }
  }

  // Result grid columns are derived from the query result; rows are indexed arrays.
  type ResultRow = { i: number; cells: CellValue[] }
  const resultRows: ResultRow[] = useMemo(
    () => (result ? result.rows.map((cells, i) => ({ i, cells })) : []),
    [result],
  )
  const resultColumns: Column<ResultRow>[] = useMemo(
    () =>
      result
        ? result.columns.map((c, idx) => ({
            key: String(idx),
            header: c.name,
            sortable: true,
            sortValue: (r: ResultRow) => {
              const v = r.cells[idx]
              return typeof v === 'number' ? v : String(v ?? '')
            },
            render: (r: ResultRow) => (
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.8rem', whiteSpace: 'pre' }}>
                <CellView value={r.cells[idx]} />
              </span>
            ),
          }))
        : [],
    [result],
  )

  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">{t('eyebrow', 'openWCS · Administration')}</div>
        <h1>{t('title', 'Database')}</h1>
        <p>{t('subtitle', 'Browse every service schema and run read-only SELECT queries against the shared database.')}</p>
      </div>

      <div style={{ display: 'flex', gap: '1rem', alignItems: 'flex-start' }}>
        {/* Schema → table tree */}
        <aside className="glass card-pad" style={{ width: 280, flexShrink: 0, maxHeight: '72vh', overflowY: 'auto' }}>
          <div style={{ fontSize: '.72rem', letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--text-dim)', marginBottom: '.5rem' }}>
            {t('schemas', 'Schemas')}
          </div>
          {schemasError && <div className="alert alert-danger">{schemasError}</div>}
          {!schemasError && schemas.length === 0 && <div className="muted">{t('loadingSchemas', 'Loading schemas…')}</div>}
          {schemas.map((schema) => {
            const open = openSchemas.has(schema.name)
            return (
              <div key={schema.name} style={{ marginBottom: '.15rem' }}>
                <button
                  type="button"
                  className="btn btn-ghost btn-sm btn-block"
                  style={{ justifyContent: 'flex-start', textAlign: 'left', fontFamily: 'var(--font-mono)' }}
                  onClick={() => toggleSchema(schema.name)}
                  aria-expanded={open}
                >
                  <span style={{ color: 'var(--herbal-lime)', marginRight: '.4rem' }}>{open ? '▾' : '▸'}</span>
                  {schema.name}
                  <span style={{ color: 'var(--text-dim)', marginLeft: '.4rem', fontSize: '.72rem' }}>
                    {schema.tables.length}
                  </span>
                </button>
                {open &&
                  (schema.tables.length === 0 ? (
                    <div className="muted" style={{ padding: '.1rem 0 .3rem 1.6rem', fontSize: '.78rem' }}>{t('noTables', 'No tables')}</div>
                  ) : (
                    schema.tables.map((table) => {
                      const id = `${schema.name}.${table.name}`
                      const selected = selectedTable === id
                      return (
                        <div key={id} style={{ paddingLeft: '1.1rem' }}>
                          <button
                            type="button"
                            className="btn btn-ghost btn-sm btn-block"
                            style={{
                              justifyContent: 'flex-start',
                              textAlign: 'left',
                              fontFamily: 'var(--font-mono)',
                              fontSize: '.78rem',
                              color: selected ? 'var(--herbal-lime)' : undefined,
                            }}
                            title={table.columns.map((c) => `${c.name} : ${c.type}`).join('\n')}
                            onClick={() => openTable(schema.name, table.name)}
                          >
                            {table.name}
                          </button>
                          {/* Column metadata for the selected table, right under its name. */}
                          {selected && (
                            <ul style={{ listStyle: 'none', margin: '0 0 .35rem', padding: '0 0 0 1rem' }}>
                              {table.columns.map((c) => (
                                <li key={c.name} style={{ fontSize: '.72rem', fontFamily: 'var(--font-mono)', display: 'flex', gap: '.4rem', justifyContent: 'space-between' }}>
                                  <span>{c.name}</span>
                                  <span style={{ color: 'var(--text-dim)' }}>{c.type}</span>
                                </li>
                              ))}
                            </ul>
                          )}
                        </div>
                      )
                    })
                  ))}
              </div>
            )
          })}
        </aside>

        {/* Editor + results */}
        <div style={{ flex: 1, minWidth: 0 }}>
          <section className="glass card-pad" style={{ marginBottom: '1rem' }}>
            <textarea
              className="form-control"
              value={sql}
              onChange={(e) => setSql(e.target.value)}
              onKeyDown={onEditorKeyDown}
              rows={6}
              spellCheck={false}
              aria-label={t('sqlQuery', 'SQL query')}
              placeholder="select * from master_data.sku limit 100"
              style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: '.85rem', resize: 'vertical' }}
            />
            <div style={{ display: 'flex', alignItems: 'center', gap: '.8rem', marginTop: '.6rem', flexWrap: 'wrap' }}>
              <button type="button" className="btn btn-primary btn-sm" onClick={() => void execute(sql)} disabled={running || !sql.trim()}>
                {running ? t('running', 'Running…') : t('run', 'Run')}
              </button>
              <span style={{ color: 'var(--text-dim)', fontSize: '.75rem' }}>{t('editorHint', '⌘/Ctrl+Enter runs · SELECT only · read-only')}</span>
              <div style={{ flex: 1 }} />
              {result && (
                <span style={{ display: 'flex', alignItems: 'center', gap: '.5rem', fontSize: '.8rem' }}>
                  <span className="badge">{result.rowCount} {result.rowCount === 1 ? t('row', 'row') : t('rows', 'rows')}</span>
                  {result.truncated && (
                    <span className="badge badge-warning" title={t('truncatedTip', 'The query returned more rows than the cap; only the first page was fetched.')}>
                      {t('truncated', 'truncated')}
                    </span>
                  )}
                  <span style={{ color: 'var(--text-dim)' }}>{result.executionMs} ms</span>
                </span>
              )}
            </div>
          </section>

          {error && (
            <div className="alert alert-danger" style={{ marginBottom: '1rem', fontFamily: 'var(--font-mono)', fontSize: '.8rem', whiteSpace: 'pre-wrap' }}>
              {error}
            </div>
          )}

          {result && (
            <DataTable<ResultRow>
              columns={resultColumns}
              rows={resultRows}
              rowKey={(r) => String(r.i)}
              search={(r) => r.cells.map((c) => String(c ?? '')).join(' ')}
              searchPlaceholder={t('filterRows', 'Filter result rows…')}
              pageSize={50}
              empty={t('noRows', 'The query returned no rows.')}
            />
          )}
          {!result && !error && (
            <div className="glass card-pad muted">
              {t('emptyHelp', 'Pick a table on the left or write a SELECT and press Run. Results are capped (default 200 rows) and every query runs in a read-only transaction with a 10 second timeout.')}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
