import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchLogDays, fetchServiceLogs } from './api'

const TAIL_OPTIONS = [200, 500, 1000, 2000]

// Shared log viewer for one service — used by both the modal (System info table) and the full
// Logs page. Reads the last N lines of a chosen day's file via the gateway and filters them
// client-side by a free-text query. `fillHeight` makes the <pre> grow to fill its container (page
// mode); otherwise it's capped (modal mode).
export default function LogViewer({ name, fillHeight = false }: { name: string; fillHeight?: boolean }) {
  const [tail, setTail] = useState(200)
  const [date, setDate] = useState('') // '' = most recent day
  const [days, setDays] = useState<string[]>([])
  const [text, setText] = useState('')
  const [err, setErr] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const preRef = useRef<HTMLPreElement>(null)

  // Load the available days once per service (newest first); reset the selection to "latest".
  useEffect(() => {
    setDate('')
    fetchLogDays(name)
      .then(setDays)
      .catch(() => setDays([]))
  }, [name])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetchServiceLogs(name, tail, date || undefined)
      setText(res.logs ?? '')
      setErr(res.error ?? null)
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e))
      setText('')
    } finally {
      setLoading(false)
    }
  }, [name, tail, date])

  useEffect(() => {
    load()
  }, [load])

  // Auto-scroll to the newest line — but only when not filtering (a filter wants the top of results).
  useEffect(() => {
    if (!query && preRef.current) preRef.current.scrollTop = preRef.current.scrollHeight
  }, [text, query])

  const lines = text ? text.split('\n') : []
  const q = query.trim().toLowerCase()
  const shown = q ? lines.filter((l) => l.toLowerCase().includes(q)) : lines

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '.5rem', flex: fillHeight ? 1 : undefined, minHeight: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '.5rem', flexWrap: 'wrap' }}>
        <input
          className="form-control"
          placeholder="Filter lines…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1, minWidth: 160 }}
        />
        <label style={{ fontSize: '.78rem', color: 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: '.3rem' }}>
          Day
          <select
            className="form-control"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            style={{ padding: '.15rem .35rem', height: 'auto' }}
          >
            <option value="">Latest{days[0] ? ` (${days[0]})` : ''}</option>
            {days.map((d) => (
              <option key={d} value={d}>{d}</option>
            ))}
          </select>
        </label>
        <label style={{ fontSize: '.78rem', color: 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: '.3rem' }}>
          Tail
          <select
            className="form-control"
            value={tail}
            onChange={(e) => setTail(Number(e.target.value))}
            style={{ padding: '.15rem .35rem', height: 'auto' }}
          >
            {TAIL_OPTIONS.map((n) => (
              <option key={n} value={n}>{n}</option>
            ))}
          </select>
        </label>
        <button type="button" className="btn btn-outline btn-sm" onClick={load} disabled={loading}>
          {loading ? 'Loading…' : 'Refresh'}
        </button>
        {q && (
          <span style={{ fontSize: '.75rem', color: 'var(--text-dim)' }}>
            {shown.length} / {lines.length} lines
          </span>
        )}
      </div>
      {err && <div className="alert alert-danger">{err}</div>}
      <pre
        ref={preRef}
        style={{
          margin: 0,
          flex: fillHeight ? 1 : undefined,
          maxHeight: fillHeight ? undefined : '60vh',
          overflow: 'auto',
          background: '#081e16',
          color: '#cfe',
          borderRadius: 8,
          padding: '.7rem .8rem',
          fontSize: '.72rem',
          lineHeight: 1.45,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
        }}
      >
        {loading && !text
          ? 'Loading logs…'
          : shown.length
            ? shown.join('\n')
            : err
              ? ''
              : q
                ? 'No matching lines.'
                : 'No log output.'}
      </pre>
    </div>
  )
}
