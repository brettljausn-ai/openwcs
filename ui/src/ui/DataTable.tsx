import { Fragment, ReactNode, useEffect, useMemo, useState } from 'react'

// Reusable table with client-side search, column sort and pagination — for any list that fits in
// memory (a loaded page of rows). Lists that can reach thousands of rows should paginate/search
// server-side instead (see the Warehouse access screen). Columns are declarative; `render`
// customises a cell, `sortValue` drives sorting, `search` builds the per-row haystack. Pass
// `renderExpanded` to make rows expandable (a detail row toggled by a leading chevron).
export interface Column<T> {
  key: string
  header: string
  render?: (row: T) => ReactNode
  sortValue?: (row: T) => string | number
  sortable?: boolean
  align?: 'left' | 'right' | 'center'
  width?: number | string
}

interface DataTableProps<T> {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  search?: (row: T) => string
  searchPlaceholder?: string
  pageSize?: number
  initialSort?: { key: string; dir: 'asc' | 'desc' }
  empty?: ReactNode
  onRowClick?: (row: T) => void
  renderExpanded?: (row: T) => ReactNode
  toolbarExtra?: ReactNode
}

export default function DataTable<T>({
  columns,
  rows,
  rowKey,
  search,
  searchPlaceholder = 'Search…',
  pageSize = 25,
  initialSort,
  empty = 'No rows.',
  onRowClick,
  renderExpanded,
  toolbarExtra,
}: DataTableProps<T>) {
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<{ key: string; dir: 'asc' | 'desc' } | null>(initialSort ?? null)
  const [page, setPage] = useState(0)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  const filtered = useMemo(() => {
    if (!search || !query.trim()) return rows
    const q = query.trim().toLowerCase()
    return rows.filter((r) => search(r).toLowerCase().includes(q))
  }, [rows, query, search])

  const sorted = useMemo(() => {
    if (!sort) return filtered
    const col = columns.find((c) => c.key === sort.key)
    if (!col) return filtered
    const get = col.sortValue ?? ((r: T) => {
      const v = (r as Record<string, unknown>)[col.key]
      return typeof v === 'number' ? v : String(v ?? '')
    })
    const dir = sort.dir === 'asc' ? 1 : -1
    return [...filtered].sort((a, b) => {
      const av = get(a)
      const bv = get(b)
      if (av < bv) return -dir
      if (av > bv) return dir
      return 0
    })
  }, [filtered, sort, columns])

  const total = sorted.length
  const pageCount = pageSize > 0 ? Math.max(1, Math.ceil(total / pageSize)) : 1
  const current = Math.min(page, pageCount - 1)
  const pageRows = pageSize > 0 ? sorted.slice(current * pageSize, current * pageSize + pageSize) : sorted
  const colSpan = columns.length + (renderExpanded ? 1 : 0)

  // Snap back to the first page whenever the filtered set changes underneath us.
  useEffect(() => setPage(0), [query, total])

  function toggleSort(key: string) {
    setSort((prev) => {
      if (!prev || prev.key !== key) return { key, dir: 'asc' }
      if (prev.dir === 'asc') return { key, dir: 'desc' }
      return null
    })
  }

  function toggleExpand(key: string) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  return (
    <div className="data-table">
      {(search || toolbarExtra) && (
        <div className="data-table-toolbar">
          {search && (
            <input
              className="form-control data-table-search"
              type="search"
              placeholder={searchPlaceholder}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          )}
          <span className="muted data-table-count">{total} {total === 1 ? 'row' : 'rows'}</span>
          <div style={{ flex: 1 }} />
          {toolbarExtra}
        </div>
      )}
      <div className="data-table-scroll">
        <table>
          <thead>
            <tr>
              {renderExpanded && <th style={{ width: '1%' }} aria-label="Expand" />}
              {columns.map((c) => (
                <th
                  key={c.key}
                  style={{ textAlign: c.align ?? 'left', width: c.width }}
                  className={c.sortable ? 'is-sortable' : undefined}
                  onClick={c.sortable ? () => toggleSort(c.key) : undefined}
                >
                  {c.header}
                  {c.sortable && (
                    <span className="sort-ind" aria-hidden="true">
                      {sort?.key === c.key ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : ' ⇅'}
                    </span>
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {pageRows.length === 0 ? (
              <tr>
                <td colSpan={colSpan} className="muted" style={{ textAlign: 'center', padding: '1.5rem' }}>
                  {empty}
                </td>
              </tr>
            ) : (
              pageRows.map((r) => {
                const key = rowKey(r)
                const isOpen = expanded.has(key)
                const clickable = !!renderExpanded || !!onRowClick
                return (
                  <Fragment key={key}>
                    <tr
                      className={clickable ? 'is-clickable' : undefined}
                      onClick={renderExpanded ? () => toggleExpand(key) : onRowClick ? () => onRowClick(r) : undefined}
                    >
                      {renderExpanded && (
                        <td style={{ color: 'var(--herbal-lime)', fontFamily: 'var(--font-mono)' }}>
                          {isOpen ? '▾' : '▸'}
                        </td>
                      )}
                      {columns.map((c) => (
                        <td key={c.key} style={{ textAlign: c.align ?? 'left' }}>
                          {c.render ? c.render(r) : String((r as Record<string, unknown>)[c.key] ?? '')}
                        </td>
                      ))}
                    </tr>
                    {renderExpanded && isOpen && (
                      <tr>
                        <td colSpan={colSpan} style={{ background: 'rgba(8, 30, 22, .35)' }}>
                          {renderExpanded(r)}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })
            )}
          </tbody>
        </table>
      </div>
      {pageSize > 0 && pageCount > 1 && (
        <div className="data-table-pager">
          <button className="btn btn-ghost btn-sm" disabled={current === 0} onClick={() => setPage(current - 1)}>
            Prev
          </button>
          <span className="muted">Page {current + 1} of {pageCount}</span>
          <button className="btn btn-ghost btn-sm" disabled={current >= pageCount - 1} onClick={() => setPage(current + 1)}>
            Next
          </button>
        </div>
      )}
    </div>
  )
}
