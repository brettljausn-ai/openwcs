// A dependency-free, accessible type-to-filter combobox for picking a TASK from the catalog. Prod
// systems may advertise ~100 tasks, so a plain <select> is painful to scan; this mirrors the
// VarCombobox pattern but lists tasks: the input shows the selected task's label, typing filters
// across the label, type id and description, and each row shows the label + a dim secondary line
// (type id and/or a short description). Keyboard: ArrowDown/ArrowUp move the highlight, Enter selects,
// Escape closes; click selects.
//
// The selected VALUE is the task type id (matching the <select> contract it replaces).
//
// Rules of Hooks: all hooks run unconditionally at the top; the render below reads from state.

import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { TaskTypeDef } from '../model'

interface Props {
  /** The currently selected task type id. */
  value: string
  /** The catalog tasks to choose from. */
  options: TaskTypeDef[]
  /** Called with the chosen task type id. */
  onChange: (id: string) => void
  placeholder?: string
  /** Optional id for the input (label association). */
  id?: string
}

export default function TaskCombobox({ value, options, onChange, placeholder, id }: Props) {
  const autoId = useId()
  const inputId = id ?? autoId
  const listboxId = `${inputId}-listbox`

  const selected = useMemo(() => options.find((o) => o.id === value), [options, value])
  // The label shown in the input when closed (falls back to the raw id for an unknown task).
  const selectedLabel = selected?.label ?? value

  const [open, setOpen] = useState(false)
  // The text in the input. When closed it mirrors the selected task's label; while open it is the raw
  // query used to filter.
  const [query, setQuery] = useState('')
  const [active, setActive] = useState(0)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  // Filter across label + type id + description (case-insensitive). Empty query shows everything.
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return options
    return options.filter((o) =>
      o.label.toLowerCase().includes(q) ||
      o.id.toLowerCase().includes(q) ||
      (o.description ?? '').toLowerCase().includes(q),
    )
  }, [query, options])

  // Close on an outside click / focus leaving the widget.
  useEffect(() => {
    if (!open) return
    const onDocPointer = (e: PointerEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', onDocPointer)
    return () => document.removeEventListener('pointerdown', onDocPointer)
  }, [open])

  // Keep the active index in range when the filtered list shrinks.
  useEffect(() => {
    if (active >= filtered.length) setActive(filtered.length > 0 ? filtered.length - 1 : 0)
  }, [filtered.length, active])

  const openList = () => {
    if (!open) {
      setOpen(true)
      setQuery('') // start filtering fresh; empty query shows all
      // Highlight the currently selected task if present.
      const idx = options.findIndex((o) => o.id === value)
      setActive(idx >= 0 ? idx : 0)
    }
  }

  const select = (taskId: string) => {
    onChange(taskId)
    setOpen(false)
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
        select(filtered[active].id)
      }
    } else if (e.key === 'Escape') {
      if (open) {
        e.preventDefault()
        e.stopPropagation()
        setOpen(false)
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
          value={open ? query : selectedLabel}
          placeholder={placeholder ?? 'Pick a task…'}
          onChange={(e) => { setQuery(e.target.value); if (!open) setOpen(true); setActive(0) }}
          onFocus={openList}
          onClick={openList}
          onKeyDown={onKeyDown}
        />
      </div>

      {open && (
        <ul className="op-pd-combobox-list op-pd-taskcombo-list" id={listboxId} role="listbox">
          {filtered.length === 0 ? (
            <li className="op-pd-combobox-empty" role="option" aria-disabled="true" aria-selected={false}>
              No matches
            </li>
          ) : (
            filtered.map((o, i) => (
              <li
                key={o.id}
                id={`${listboxId}-opt-${i}`}
                role="option"
                aria-selected={o.id === value}
                className={`op-pd-combobox-opt op-pd-taskcombo-opt${i === active ? ' is-active' : ''}${o.id === value ? ' is-selected' : ''}`}
                onMouseEnter={() => setActive(i)}
                onMouseDown={(e) => { e.preventDefault(); select(o.id) }}
              >
                <span className="op-pd-taskcombo-main">
                  <span className="op-pd-combobox-name">{o.label}</span>
                  <span className="op-pd-taskcombo-secondary">
                    <code className="op-pd-taskcombo-id">{o.id}</code>
                    {o.description ? <span className="op-pd-taskcombo-desc"> — {o.description}</span> : null}
                  </span>
                </span>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  )
}
