import { CSSProperties, KeyboardEvent, useEffect, useId, useRef, useState } from 'react'

// Styled, accessible single-select to replace the native <select> across the app. Renders a
// button + popover listbox (role=listbox/option) with full keyboard support (↑/↓, Enter, Esc,
// Home/End), click-outside to close, and the dark/lime theme. Keep the API close to a native
// select: controlled `value` + `onChange(value)` over a flat `options` list.
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
  const rootRef = useRef<HTMLDivElement>(null)
  const listId = useId()
  const selected = options.find((o) => o.value === value) ?? null

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
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
      {open && (
        <ul className="select-menu" role="listbox" id={listId} aria-label={ariaLabel}>
          {options.length === 0 && <li className="select-empty">No options</li>}
          {options.map((o, i) => (
            <li
              key={o.value}
              role="option"
              aria-selected={o.value === value}
              className={`select-option${o.value === value ? ' is-selected' : ''}${i === active ? ' is-active' : ''}${o.disabled ? ' is-disabled' : ''}`}
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
        </ul>
      )}
    </div>
  )
}
