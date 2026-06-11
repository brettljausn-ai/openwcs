import { CSSProperties, KeyboardEvent, useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

// Styled, accessible single-select to replace the native <select> across the app. Renders a
// button + popover listbox (role=listbox/option) with full keyboard support (↑/↓, Enter, Esc,
// Home/End), click-outside to close, and the dark/lime theme. The menu is PORTALLED to <body>
// with fixed positioning so it's never clipped or painted behind a sibling card (glass cards'
// backdrop-filter creates stacking contexts that would otherwise trap an absolutely-positioned
// menu). Keep the API close to a native select: controlled `value` + `onChange(value)`.
export interface SelectOption {
  value: string
  label: string
  hint?: string
  disabled?: boolean
}

interface SelectProps {
  value: string
  onChange: (value: string) => void
  options: SelectOption[]
  placeholder?: string
  disabled?: boolean
  ariaLabel?: string
  className?: string
  style?: CSSProperties
}

export default function Select({
  value,
  onChange,
  options,
  placeholder = 'Select…',
  disabled,
  ariaLabel,
  className,
  style,
}: SelectProps) {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(-1)
  const [rect, setRect] = useState<{ top: number; left: number; width: number } | null>(null)
  // The menu's clamped left edge: it sizes to its CONTENT (capped), so once rendered we measure it
  // and shift it left if it would overflow the right viewport edge. Null until measured.
  const [menuLeft, setMenuLeft] = useState<number | null>(null)
  const rootRef = useRef<HTMLDivElement>(null)
  const menuRef = useRef<HTMLUListElement>(null)
  const listId = useId()
  const selected = options.find((o) => o.value === value) ?? null

  const reposition = useCallback(() => {
    const el = rootRef.current
    if (!el) return
    const r = el.getBoundingClientRect()
    setRect({ top: r.bottom + 4, left: r.left, width: r.width })
  }, [])

  // Measure before paint when opening, and follow scroll/resize while open.
  useLayoutEffect(() => {
    if (!open) {
      setMenuLeft(null)
      return
    }
    reposition()
    const onScroll = () => reposition()
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onScroll)
    return () => {
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onScroll)
    }
  }, [open, reposition])

  // After the menu renders (content-sized), keep it inside the viewport: align to the trigger's
  // left edge, but shift left when the wider-than-trigger menu would spill off the right side.
  useLayoutEffect(() => {
    if (!open || !rect) return
    const w = menuRef.current?.getBoundingClientRect().width ?? rect.width
    setMenuLeft(Math.max(8, Math.min(rect.left, window.innerWidth - 8 - w)))
  }, [open, rect, options])

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      const t = e.target as Node
      if (rootRef.current?.contains(t) || menuRef.current?.contains(t)) return
      setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  useEffect(() => {
    if (open) setActive(options.findIndex((o) => o.value === value))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  function commit(i: number) {
    const opt = options[i]
    if (opt && !opt.disabled) {
      onChange(opt.value)
      setOpen(false)
    }
  }

  function move(delta: number) {
    setActive((prev) => {
      let i = prev < 0 ? (delta > 0 ? -1 : options.length) : prev
      for (let n = 0; n < options.length; n++) {
        i = (i + delta + options.length) % options.length
        if (!options[i]?.disabled) return i
      }
      return prev
    })
  }

  function onKeyDown(e: KeyboardEvent) {
    if (disabled) return
    if (!open) {
      if (e.key === 'Enter' || e.key === ' ' || e.key === 'ArrowDown') {
        e.preventDefault()
        setOpen(true)
      }
      return
    }
    switch (e.key) {
      case 'ArrowDown': e.preventDefault(); move(1); break
      case 'ArrowUp': e.preventDefault(); move(-1); break
      case 'Home': e.preventDefault(); setActive(options.findIndex((o) => !o.disabled)); break
      case 'End': e.preventDefault(); move(-1); break
      case 'Enter': case ' ': e.preventDefault(); commit(active); break
      case 'Escape': e.preventDefault(); setOpen(false); break
      case 'Tab': setOpen(false); break
      default: break
    }
  }

  return (
    <div
      ref={rootRef}
      className={`select${open ? ' is-open' : ''}${disabled ? ' is-disabled' : ''}${className ? ` ${className}` : ''}`}
      style={style}
    >
      <button
        type="button"
        className="select-trigger"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        disabled={disabled}
        onClick={() => !disabled && setOpen((o) => !o)}
        onKeyDown={onKeyDown}
      >
        <span className={`select-value${selected ? '' : ' is-placeholder'}`}>
          {selected ? selected.label : placeholder}
        </span>
        <span className="select-chevron" aria-hidden="true">▾</span>
      </button>
      {open && rect && createPortal(
        <ul
          ref={menuRef}
          className="select-menu"
          role="listbox"
          id={listId}
          aria-label={ariaLabel}
          style={{
            position: 'fixed',
            top: rect.top,
            left: menuLeft ?? rect.left,
            // Size to the content so long option labels stay readable; never narrower than the
            // trigger, capped so a pathological label can't span the screen, and kept on-screen
            // by the menuLeft clamp above.
            width: 'max-content',
            minWidth: rect.width,
            maxWidth: Math.min(420, window.innerWidth - 16),
          }}
        >
          {options.length === 0 && <li className="select-empty">No options</li>}
          {options.map((o, i) => (
            <li
              key={o.value}
              role="option"
              aria-selected={o.value === value}
              className={`select-option${o.value === value ? ' is-selected' : ''}${i === active ? ' is-active' : ''}${o.disabled ? ' is-disabled' : ''}`}
              title={o.hint ? `${o.label} — ${o.hint}` : o.label}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => {
                e.preventDefault()
                commit(i)
              }}
            >
              <span className="select-option-text">
                <span className="select-option-label">{o.label}</span>
                {o.hint && <span className="select-option-hint">{o.hint}</span>}
              </span>
              {o.value === value && <span className="select-check" aria-hidden="true">✓</span>}
            </li>
          ))}
        </ul>,
        document.body,
      )}
    </div>
  )
}
