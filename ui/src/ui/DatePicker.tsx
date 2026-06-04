import { CSSProperties, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

// Themed date / datetime picker replacing native <input type="date|datetime-local"> (whose popup
// calendar can't be styled). Renders a styled trigger + a portalled, fixed-positioned calendar
// popover (matching the Select look) so it's never clipped behind a card. Value uses the native
// input formats so it's a drop-in: 'YYYY-MM-DD' (date) or 'YYYY-MM-DDTHH:mm' (datetime).
interface DatePickerProps {
  value: string
  onChange: (value: string) => void
  withTime?: boolean
  placeholder?: string
  ariaLabel?: string
  className?: string
  style?: CSSProperties
}

const DOW = ['M', 'T', 'W', 'T', 'F', 'S', 'S']

function pad(n: number): string {
  return String(n).padStart(2, '0')
}
function parse(v: string): Date | null {
  if (!v) return null
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d
}
function fmtValue(d: Date, withTime: boolean): string {
  const base = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
  return withTime ? `${base}T${pad(d.getHours())}:${pad(d.getMinutes())}` : base
}
function fmtDisplay(d: Date, withTime: boolean): string {
  return d.toLocaleString(undefined, withTime
    ? { year: 'numeric', month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' }
    : { year: 'numeric', month: 'short', day: '2-digit' })
}
function monthGrid(year: number, month: number): (Date | null)[] {
  const startDow = (new Date(year, month, 1).getDay() + 6) % 7 // Monday = 0
  const days = new Date(year, month + 1, 0).getDate()
  const cells: (Date | null)[] = []
  for (let i = 0; i < startDow; i++) cells.push(null)
  for (let d = 1; d <= days; d++) cells.push(new Date(year, month, d))
  while (cells.length % 7 !== 0) cells.push(null)
  return cells
}
function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}

export default function DatePicker({
  value,
  onChange,
  withTime,
  placeholder = withTime ? 'Pick date & time' : 'Pick a date',
  ariaLabel,
  className,
  style,
}: DatePickerProps) {
  const selected = parse(value)
  const [open, setOpen] = useState(false)
  const [view, setView] = useState(() => selected ?? new Date())
  const [rect, setRect] = useState<{ top: number; left: number } | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const popRef = useRef<HTMLDivElement>(null)

  const reposition = useCallback(() => {
    const el = rootRef.current
    if (!el) return
    const r = el.getBoundingClientRect()
    setRect({ top: r.bottom + 4, left: r.left })
  }, [])

  useLayoutEffect(() => {
    if (!open) return
    setView(selected ?? new Date())
    reposition()
    const onScroll = () => reposition()
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onScroll)
    return () => {
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onScroll)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, reposition])

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      const t = e.target as Node
      if (rootRef.current?.contains(t) || popRef.current?.contains(t)) return
      setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  function pickDay(day: Date) {
    const base = selected ?? new Date(day.getFullYear(), day.getMonth(), day.getDate(), 0, 0)
    const next = new Date(day.getFullYear(), day.getMonth(), day.getDate(), base.getHours(), base.getMinutes())
    onChange(fmtValue(next, !!withTime))
    if (!withTime) setOpen(false)
  }
  function setTime(hours: number, minutes: number) {
    const base = selected ?? new Date()
    const next = new Date(base.getFullYear(), base.getMonth(), base.getDate(), hours, minutes)
    onChange(fmtValue(next, true))
  }

  const today = new Date()
  const cells = monthGrid(view.getFullYear(), view.getMonth())

  return (
    <div ref={rootRef} className={`datepicker${open ? ' is-open' : ''}${className ? ` ${className}` : ''}`} style={style}>
      <button
        type="button"
        className="select-trigger"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => setOpen((o) => !o)}
      >
        <span className={`select-value${selected ? '' : ' is-placeholder'}`}>
          {selected ? fmtDisplay(selected, !!withTime) : placeholder}
        </span>
        <span className="select-chevron" aria-hidden="true">▾</span>
      </button>
      {open && rect && createPortal(
        <div
          ref={popRef}
          className="datepicker-pop select-menu"
          role="dialog"
          aria-label={ariaLabel}
          style={{ position: 'fixed', top: rect.top, left: rect.left, width: 'auto' }}
        >
          <div className="dp-head">
            <button type="button" className="dp-nav" aria-label="Previous month"
              onClick={() => setView(new Date(view.getFullYear(), view.getMonth() - 1, 1))}>‹</button>
            <span className="dp-month">{view.toLocaleString(undefined, { month: 'long', year: 'numeric' })}</span>
            <button type="button" className="dp-nav" aria-label="Next month"
              onClick={() => setView(new Date(view.getFullYear(), view.getMonth() + 1, 1))}>›</button>
          </div>
          <div className="dp-grid">
            {DOW.map((d, i) => <span key={i} className="dp-dow">{d}</span>)}
            {cells.map((c, i) => c === null ? (
              <span key={i} />
            ) : (
              <button
                key={i}
                type="button"
                className={`dp-day${selected && sameDay(c, selected) ? ' is-selected' : ''}${sameDay(c, today) ? ' is-today' : ''}`}
                onClick={() => pickDay(c)}
              >
                {c.getDate()}
              </button>
            ))}
          </div>
          {withTime && (
            <div className="dp-time">
              <span className="muted">Time</span>
              <input className="form-control dp-time-input" type="number" min={0} max={23} aria-label="Hour"
                value={selected ? pad(selected.getHours()) : '00'}
                onChange={(e) => setTime(Math.min(23, Math.max(0, Number(e.target.value) || 0)), selected?.getMinutes() ?? 0)} />
              <span>:</span>
              <input className="form-control dp-time-input" type="number" min={0} max={59} aria-label="Minute"
                value={selected ? pad(selected.getMinutes()) : '00'}
                onChange={(e) => setTime(selected?.getHours() ?? 0, Math.min(59, Math.max(0, Number(e.target.value) || 0)))} />
            </div>
          )}
          <div className="dp-foot">
            <button type="button" className="dp-link" onClick={() => { onChange(''); setOpen(false) }}>Clear</button>
            <button type="button" className="dp-link" onClick={() => { onChange(fmtValue(new Date(), !!withTime)); setOpen(false) }}>Today</button>
          </div>
        </div>,
        document.body,
      )}
    </div>
  )
}
