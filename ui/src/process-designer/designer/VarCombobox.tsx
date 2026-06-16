// A lightweight, dependency-free, accessible type-to-filter combobox for picking a data-object
// VARIABLE (the data-object list grows long; a plain <select> is painful to scan). It is a controlled
// input + a filtered popup of variable names (with their type as a dim suffix). Keyboard: ArrowDown/
// ArrowUp move the highlight, Enter selects the highlighted option, Escape closes. Click selects.
// Clearable back to "(none)" when allowNone.
//
// The selected VALUE is the variable name (matching the existing <select> contract everywhere it
// replaces). onChange('') means "(none)".
//
// Rules of Hooks: all hooks run unconditionally at the top; the render below reads from state.

import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { DataVar } from '../model'

interface Props {
  /** The currently selected variable name ('' = none). */
  value: string
  /** The data-object variables to choose from. */
  options: DataVar[]
  /** Called with the chosen variable name, or '' for "(none)". */
  onChange: (name: string) => void
  placeholder?: string
  /** Show a "(none)" clear affordance + allow clearing to ''. Defaults to true. */
  allowNone?: boolean
  /** Optional id for the input (label association). */
  id?: string
}

export default function VarCombobox({ value, options, onChange, placeholder, allowNone = true, id }: Props) {
  const autoId = useId()
  const inputId = id ?? autoId
  const listboxId = `${inputId}-listbox`

  const [open, setOpen] = useState(false)
  // The text shown in the input. When closed it mirrors the selected value; while typing it is the
  // raw query used to filter. Kept in sync to the selected value whenever it changes externally.
  const [query, setQuery] = useState(value)
  const [active, setActive] = useState(0)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  // Reflect external value changes (e.g. switching the selected step) back into the input text.
  useEffect(() => {
    if (!open) setQuery(value)
  }, [value, open])

  // The filter: when the input text equals the selected value (popup just opened, no typing yet) show
  // everything; otherwise filter case-insensitively on the variable name.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q || q === value.toLowerCase()) return options
    return options.filter((o) => o.name.toLowerCase().includes(q))
  }, [query, value, options])

  // Close on an outside click / focus leaving the widget.
  useEffect(() => {
    if (!open) return
    const onDocPointer = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
        setQuery(value)
      }
    }
    document.addEventListener('pointerdown', onDocPointer)
    return () => document.removeEventListener('pointerdown', onDocPointer)
  }, [open, value])

  // Keep the active index in range when the filtered list shrinks.
  useEffect(() => {
    if (active >= filtered.length) setActive(filtered.length > 0 ? filtered.length - 1 : 0)
  }, [filtered.length, active])

  const openList = () => {
    if (!open) {
      setOpen(true)
      setQuery('') // start filtering fresh; empty query shows all
      setActive(0)
    }
  }

  const select = (name: string) => {
    onChange(name)
    setQuery(name)
    setOpen(false)
  }

  const clear = () => {
    onChange('')
    setQuery('')
    setOpen(false)
    inputRef.current?.focus()
  }

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (!open) { openList(); return }
      setActive((i) => Math.min(i + 1, filtered.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (!open) { openList(); return }
      setActive((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      if (open && filtered[active]) {
        e.preventDefault()
        select(filtered[active].name)
      }
    } else if (e.key === 'Escape') {
      if (open) {
        e.preventDefault()
        e.stopPropagation()
        setOpen(false)
        setQuery(value)
      }
    }
  }

  return (
    <div className="op-pd-combobox" ref={rootRef}>
      <div className="op-pd-combobox-control">
        <input
          id={inputId}
          ref={inputRef}
          className="op-pd-combobox-input"
          role="combobox"
          aria-expanded={open}
          aria-controls={listboxId}
          aria-autocomplete="list"
          aria-activedescendant={open && filtered[active] ? `${listboxId}-opt-${active}` : undefined}
          autoComplete="off"
          spellCheck={false}
          value={open ? query : value}
          placeholder={placeholder ?? (allowNone ? '(none)' : 'Pick a variable…')}
          onChange={(e) => { setQuery(e.target.value); if (!open) setOpen(true); setActive(0) }}
          onFocus={openList}
          onClick={openList}
          onKeyDown={onKeyDown}
        />
        {allowNone && value && (
          <button
            type="button"
            className="op-pd-combobox-clear"
            aria-label="Clear"
            title="Clear"
            tabIndex={-1}
            onClick={clear}
          >
            ✕
          </button>
        )}
      </div>

      {open && (
        <ul className="op-pd-combobox-list" id={listboxId} role="listbox">
          {allowNone && (
            <li
              id={`${listboxId}-opt-none`}
              role="option"
              aria-selected={value === ''}
              className={`op-pd-combobox-opt op-pd-combobox-none${value === '' ? ' is-selected' : ''}`}
              onMouseDown={(e) => { e.preventDefault(); select('') }}
            >
              (none)
            </li>
          )}
          {filtered.length === 0 ? (
            <li className="op-pd-combobox-empty" role="option" aria-disabled="true" aria-selected={false}>
              No matches
            </li>
          ) : (
            filtered.map((o, i) => (
              <li
                key={o.name}
                id={`${listboxId}-opt-${i}`}
                role="option"
                aria-selected={o.name === value}
                className={`op-pd-combobox-opt${i === active ? ' is-active' : ''}${o.name === value ? ' is-selected' : ''}`}
                onMouseEnter={() => setActive(i)}
                onMouseDown={(e) => { e.preventDefault(); select(o.name) }}
              >
                <span className="op-pd-combobox-name">{o.name}</span>
                <span className="op-pd-combobox-type">{o.type}</span>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  )
}
