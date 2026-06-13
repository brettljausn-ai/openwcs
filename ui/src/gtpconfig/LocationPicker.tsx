import { useEffect, useMemo, useRef, useState } from 'react'
import { listLocations, type Location } from '../masterdata/api'
import { useT } from '../i18n/useT'

// A single-value picker for a master-data location, searchable by CODE (not raw UUID). Stores the
// location's id in `value` but shows/searches its code. Loads the warehouse's locations once.
export default function LocationPicker({
  warehouseId,
  value,
  onChange,
  placeholder,
}: {
  warehouseId: string
  value: string // location id, '' = none
  onChange: (id: string) => void
  placeholder?: string
}) {
  const t = useT('gtpconfig')
  const [locs, setLocs] = useState<Location[]>([])
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [typing, setTyping] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!warehouseId) {
      setLocs([])
      return
    }
    let alive = true
    listLocations(warehouseId)
      .then((ls) => {
        if (alive) setLocs(ls)
      })
      .catch(() => {
        if (alive) setLocs([])
      })
    return () => {
      alive = false
    }
  }, [warehouseId])

  // Code of the currently-selected id (for display when not actively typing). Fall back to the raw
  // value if it isn't among the loaded locations (e.g. still loading, or a stale/foreign id).
  const selectedCode = useMemo(() => locs.find((l) => l.id === value)?.code ?? value, [locs, value])

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase()
    const base = q ? locs.filter((l) => l.code.toLowerCase().includes(q)) : locs
    return base.slice(0, 50)
  }, [locs, query])

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false)
        setTyping(false)
      }
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [])

  const pick = (l: Location) => {
    onChange(l.id ?? '')
    setOpen(false)
    setTyping(false)
    setQuery('')
  }

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <input
        className="form-control"
        value={typing ? query : selectedCode}
        placeholder={placeholder ?? t('searchLocationCode', 'Search a location code…')}
        onChange={(e) => {
          setTyping(true)
          setQuery(e.target.value)
          setOpen(true)
          if (e.target.value.trim() === '') onChange('')
        }}
        onFocus={() => {
          setTyping(true)
          setQuery('')
          setOpen(true)
        }}
      />
      {open && (
        <ul
          style={{
            position: 'absolute', zIndex: 20, top: 'calc(100% + 2px)', left: 0, right: 0, margin: 0,
            padding: '.25rem', listStyle: 'none', maxHeight: 220, overflowY: 'auto', borderRadius: 8,
            background: 'var(--surface, #0e1f18)', border: '1px solid var(--glass-border)',
            boxShadow: '0 8px 24px rgba(0,0,0,.35)',
          }}
        >
          {matches.length === 0 ? (
            <li style={{ padding: '.35rem .5rem', color: 'var(--text-dim)', fontSize: '.82rem' }}>
              {locs.length === 0
                ? t('noLocations', 'No locations in this warehouse.')
                : t('noMatchingCode', 'No matching code.')}
            </li>
          ) : (
            matches.map((l) => (
              <li key={l.id}>
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault()
                    pick(l)
                  }}
                  style={{
                    width: '100%', textAlign: 'left', background: l.id === value ? 'rgba(141,198,63,.12)' : 'none',
                    border: 'none', cursor: 'pointer', color: 'var(--text)', padding: '.35rem .5rem',
                    borderRadius: 6, fontFamily: 'var(--font-mono)', fontSize: '.82rem',
                  }}
                >
                  {l.code}
                  <span style={{ color: 'var(--text-dim)', fontFamily: 'var(--font-body)' }}>
                    {' · '}
                    {l.locationType}
                  </span>
                </button>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  )
}
