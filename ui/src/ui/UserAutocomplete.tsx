import { useEffect, useRef, useState } from 'react'
import { searchUsers, KcUser } from '../users/api'

interface Props {
  value: string[]
  onChange: (usernames: string[]) => void
  placeholder?: string
  ariaLabel?: string
}

function fullName(u: KcUser): string {
  return [u.firstName, u.lastName].filter(Boolean).join(' ').trim()
}

/**
 * Multi-value tag input that only accepts EXISTING Keycloak usernames.
 *
 * Selected usernames render as removable chips; typing fires a debounced search against
 * Keycloak and shows a dropdown of suggestions. Only a clicked/Enter-selected suggestion
 * can be added — there is no free-text commit of arbitrary input. Backspace on an empty
 * input removes the last chip.
 */
export default function UserAutocomplete({ value, onChange, placeholder, ariaLabel }: Props) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [suggestions, setSuggestions] = useState<KcUser[]>([])
  const [active, setActive] = useState(0)
  const [loading, setLoading] = useState(false)

  const rootRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  // Debounced search against Keycloak. Suggestions exclude already-selected usernames.
  useEffect(() => {
    const q = query.trim()
    if (q.length === 0) {
      setSuggestions([])
      setLoading(false)
      return
    }
    setLoading(true)
    let cancelled = false
    const handle = window.setTimeout(() => {
      searchUsers({ search: q, max: 8 })
        .then((users) => {
          if (cancelled) return
          const taken = new Set(value)
          setSuggestions(users.filter((u) => !taken.has(u.username)))
          setActive(0)
        })
        .catch(() => {
          if (!cancelled) setSuggestions([])
        })
        .finally(() => {
          if (!cancelled) setLoading(false)
        })
    }, 250)
    return () => {
      cancelled = true
      window.clearTimeout(handle)
    }
  }, [query, value])

  // Click-outside closes the dropdown.
  useEffect(() => {
    if (!open) return
    function onDocMouseDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onDocMouseDown)
    return () => document.removeEventListener('mousedown', onDocMouseDown)
  }, [open])

  function addUser(username: string) {
    if (!value.includes(username)) onChange([...value, username])
    setQuery('')
    setSuggestions([])
    setActive(0)
    setOpen(false)
    inputRef.current?.focus()
  }

  function removeUser(username: string) {
    onChange(value.filter((u) => u !== username))
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Backspace' && query.length === 0 && value.length > 0) {
      e.preventDefault()
      removeUser(value[value.length - 1])
      return
    }
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (suggestions.length > 0) {
        setOpen(true)
        setActive((a) => (a + 1) % suggestions.length)
      }
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (suggestions.length > 0) {
        setOpen(true)
        setActive((a) => (a - 1 + suggestions.length) % suggestions.length)
      }
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      const pick = suggestions[active]
      if (pick) addUser(pick.username)
      return
    }
    if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  const showMenu = open && query.trim().length > 0

  return (
    <div className="user-ac" ref={rootRef}>
      <div className="user-ac-control form-control" onClick={() => inputRef.current?.focus()}>
        {value.map((username) => (
          <span key={username} className="badge badge-info user-ac-chip">
            {username}
            <button
              type="button"
              className="user-ac-chip-remove"
              aria-label={`Remove ${username}`}
              onClick={(e) => {
                e.stopPropagation()
                removeUser(username)
              }}
            >
              ×
            </button>
          </span>
        ))}
        <input
          ref={inputRef}
          className="user-ac-input"
          type="text"
          value={query}
          placeholder={value.length === 0 ? (placeholder ?? 'Add user…') : ''}
          aria-label={ariaLabel ?? 'Allowed users'}
          autoComplete="off"
          role="combobox"
          aria-expanded={showMenu}
          aria-autocomplete="list"
          onChange={(e) => {
            setQuery(e.target.value)
            setOpen(true)
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKeyDown}
        />
      </div>

      {showMenu && (
        <ul className="select-menu user-ac-menu" role="listbox">
          {loading && suggestions.length === 0 ? (
            <li className="select-empty">Searching…</li>
          ) : suggestions.length === 0 ? (
            <li className="select-empty">No matching users</li>
          ) : (
            suggestions.map((u, i) => {
              const name = fullName(u)
              return (
                <li
                  key={u.id}
                  role="option"
                  aria-selected={i === active}
                  className={`select-option${i === active ? ' is-active' : ''}`}
                  onMouseEnter={() => setActive(i)}
                  onMouseDown={(e) => {
                    e.preventDefault()
                    addUser(u.username)
                  }}
                >
                  <span className="select-option-text">
                    <span className="select-option-label">{u.username}</span>
                    {name && <span className="select-option-hint">{name}</span>}
                  </span>
                </li>
              )
            })
          )}
        </ul>
      )}
    </div>
  )
}
