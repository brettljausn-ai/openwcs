// THE shared handheld screen renderer (spec §10 "WYSIWYG = real components"). One component renders
// each of the six screen types as the actual glove-friendly, high-contrast operator screen, and is
// reused by BOTH the runtime (process-designer/runtime) and the designer's live preview. There is no
// second, cruder renderer anywhere — the designer literally previews the operator screen.
//
// Look & feel matches the picking screen: a .glass hero card, big type, large touch targets, the
// herbal-lime accent, the same validation/scan affordances.
//
// Placeholders in header/detail resolve via resolvePlaceholders against the data object (domain vars
// -> codes/labels through the catalog; unknown -> visible empty marker). Per-type validation runs on
// submit with inline errors. scanBinding inputs auto-focus and accept a keyboard-wedge burst.
//
// Rules of Hooks: ALL hooks are declared at the top, unconditionally, before any branch or early
// return (the #310 hook-after-early-return crash). The per-type body is rendered from already-
// computed state, never gating a hook.

import { useEffect, useMemo, useRef, useState } from 'react'
import type { Catalog } from '../../lib/useCatalog'
import {
  type ScreenConfig,
  type ScreenStep,
  type ScreenType,
} from '../model'
import { resolvePlaceholders } from '../placeholders'
import type { DataVar } from '../model'

export interface ProcessScreenViewProps {
  step: ScreenStep
  /** Convenience: the screen type (== step.screen). */
  config: ScreenConfig
  data: Record<string, unknown>
  schema: DataVar[]
  catalog?: Pick<Catalog, 'skuCode' | 'skuLabel' | 'locationCode' | 'equipmentLabel'>
  /** Called with the value to write (already validated). undefined for acknowledge/no-capture. */
  onSubmit: (value: unknown) => void
  /** Optional back affordance; hidden when not provided. */
  onBack?: () => void
  /** Disable inputs (read-only access / preview-without-interaction). */
  disabled?: boolean
  /** Compact = inside the designer's phone frame (tighter paddings, no document-level scan capture). */
  compact?: boolean
  /** Verify-on-submit state driven by the runtime (the screen has a `verify` block). 'checking' shows
   *  a spinner + disables the action; 'notfound' shows a "scan again" error; 'offline' shows the
   *  connection-needed message. 'idle' (or absent) is the normal state. */
  verifyState?: 'idle' | 'checking' | 'notfound' | 'offline'
  /** A subtle note surfaced under the input (e.g. "multiple matches" when verify is ambiguous). */
  verifyNote?: string | null
  /** Bump this number to clear + refocus the input (used by the runtime's verify re-prompt). */
  resetSignal?: number
  /** Optional translated labels for the verify states (runtime supplies these; English defaults). */
  verifyLabels?: {
    checking?: string
    notFound?: string
    offline?: string
  }
}

function validate(type: ScreenType, cfg: ScreenConfig, value: unknown, data: Record<string, unknown>): string | null {
  const v = cfg.validation ?? {}
  const isEmpty = value === '' || value === null || value === undefined
  if (v.required && isEmpty) return 'Required'
  if (isEmpty) return null // optional + empty = ok

  if (type === 'textInput') {
    const s = String(value)
    if (v.maxLength != null && s.length > v.maxLength) return `Max ${v.maxLength} characters`
    if (v.regex) {
      try {
        if (!new RegExp(`^(?:${v.regex})$`).test(s)) return 'Wrong format'
      } catch { /* an invalid regex in the def: skip rather than block the operator */ }
    }
    if (v.mustEqual) {
      const expected = mustEqualValue(v.mustEqual, data)
      if (expected != null && String(expected).trim().toUpperCase() !== s.trim().toUpperCase()) {
        return `Must match ${expected}`
      }
    }
  }
  if (type === 'numberInput') {
    const num = Number(value)
    if (Number.isNaN(num)) return 'Enter a number'
    if (v.integerOnly && !Number.isInteger(num)) return 'Whole numbers only'
    if (v.min != null && num < v.min) return `Min ${v.min}`
    if (v.max != null && num > v.max) return `Max ${v.max}`
    if (v.mustEqual) {
      const expected = mustEqualValue(v.mustEqual, data)
      if (expected != null && Number(expected) !== num) return `Must equal ${expected}`
    }
  }
  if (type === 'dateInput') {
    if (v.min && String(value) < String(v.min)) return `On/after ${v.min}`
    if (v.max && String(value) > String(v.max)) return `On/before ${v.max}`
  }
  return null
}

/** mustEqual carries a {{var}} (or bare var) whose resolved value the input must match. */
function mustEqualValue(spec: string, data: Record<string, unknown>): unknown {
  const m = spec.match(/^\{\{\s*([A-Za-z0-9_.]+)\s*\}\}$/)
  const name = m ? m[1] : spec
  const path = name.split('.')
  let cur: unknown = data
  for (const p of path) {
    if (cur == null || typeof cur !== 'object') return undefined
    cur = (cur as Record<string, unknown>)[p]
  }
  return cur
}

function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

export default function ProcessScreenView({
  step,
  config,
  data,
  schema,
  catalog,
  onSubmit,
  onBack,
  disabled,
  compact,
  verifyState = 'idle',
  verifyNote,
  resetSignal,
  verifyLabels,
}: ProcessScreenViewProps) {
  const type = step.screen
  const lblChecking = verifyLabels?.checking ?? 'Checking…'
  const lblNotFound = verifyLabels?.notFound ?? 'Not found, scan again'
  const lblOffline = verifyLabels?.offline ?? 'Verification needs a connection'
  const resolveCtx = useMemo(() => ({ data, schema, catalog }), [data, schema, catalog])
  const header = resolvePlaceholders(config.header, resolveCtx)
  const detail = resolvePlaceholders(config.detail, resolveCtx)

  // Local input state for the value-capturing types. Date defaults to today.
  const [text, setText] = useState('')
  const [num, setNum] = useState('')
  const [date, setDate] = useState<string>(() => todayIso())
  const [ackChecked, setAckChecked] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  // Reset capture state whenever the step changes (advancing the flow / selecting another step).
  useEffect(() => {
    setText('')
    setNum('')
    setDate(todayIso())
    setAckChecked(false)
    setError(null)
  }, [step])

  // Auto-focus the input (scanBinding wedge capture, or just for fast typing). Skip in compact
  // preview to avoid stealing focus from the designer's property fields.
  useEffect(() => {
    if (compact || disabled) return
    if (type === 'textInput' || type === 'numberInput') inputRef.current?.focus({ preventScroll: true })
  }, [type, compact, disabled, step])

  // Verify re-prompt: when the runtime bumps resetSignal (scan not found, mode=reprompt), clear the
  // captured value and refocus so the operator can scan again immediately.
  useEffect(() => {
    if (resetSignal === undefined) return
    setText('')
    setNum('')
    if (!compact && !disabled && (type === 'textInput' || type === 'numberInput')) {
      inputRef.current?.focus({ preventScroll: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetSignal])

  // --- everything below renders from state; no hooks past this point -------------------------------

  const checking = verifyState === 'checking'

  const submit = (raw: unknown, vtype: ScreenType = type) => {
    if (disabled) return
    const err = validate(vtype, config, raw, data)
    if (err) {
      setError(err)
      return
    }
    setError(null)
    onSubmit(raw)
  }

  const pad = compact ? '1rem' : '1.75rem'
  const cardStyle: React.CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    gap: compact ? '.85rem' : '1.25rem',
    padding: pad,
    borderColor: error ? 'var(--danger)' : 'rgba(141, 198, 63, .35)',
  }

  return (
    <div className="glass" style={cardStyle}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '.3rem' }}>
        <h2 style={{ margin: 0, fontSize: compact ? '1.35rem' : '1.9rem', lineHeight: 1.15 }}>
          {header || <span style={{ color: 'var(--text-dim)' }}>(no header)</span>}
        </h2>
        {detail && (
          <p style={{ margin: 0, color: 'var(--text-dim)', fontSize: compact ? '.95rem' : '1.1rem' }}>{detail}</p>
        )}
      </div>

      {config.scanBinding && (type === 'textInput' || type === 'numberInput') && (
        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: '.4rem',
            alignSelf: 'flex-start',
            fontSize: '.8rem',
            color: 'var(--herbal-lime)',
            fontWeight: 600,
          }}
        >
          <span aria-hidden="true">▮▮▯▮▯</span> Scan
        </div>
      )}

      {/* Per-type body */}
      {type === 'textInput' && (
        <input
          ref={inputRef}
          className="op-pd-input"
          value={text}
          disabled={disabled}
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          placeholder={config.scanBinding ? 'Scan…' : 'Type…'}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); submit(text) } }}
        />
      )}

      {type === 'numberInput' && (
        <input
          ref={inputRef}
          className="op-pd-input"
          value={num}
          disabled={disabled}
          inputMode="numeric"
          placeholder="0"
          onChange={(e) => setNum(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); submit(num === '' ? '' : Number(num)) } }}
        />
      )}

      {type === 'dateInput' && (
        <input
          type="date"
          className="op-pd-input"
          value={date}
          disabled={disabled}
          min={config.validation?.min}
          max={config.validation?.max}
          onChange={(e) => setDate(e.target.value)}
        />
      )}

      {type === 'acknowledge' && config.requireCheckbox && (
        <label style={{ display: 'flex', alignItems: 'center', gap: '.6rem', fontSize: '1.05rem' }}>
          <input
            type="checkbox"
            checked={ackChecked}
            disabled={disabled}
            onChange={(e) => setAckChecked(e.target.checked)}
            style={{ width: 24, height: 24 }}
          />
          {config.checkboxLabel || 'I confirm'}
        </label>
      )}

      {error && (
        <div className="badge badge-danger" style={{ alignSelf: 'flex-start', fontSize: '.95rem' }}>{error}</div>
      )}

      {/* Verify feedback (the runtime drives verifyState for screens with a `verify` block). */}
      {checking && (
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: '.5rem', alignSelf: 'flex-start', color: 'var(--text-dim)', fontSize: '.95rem' }}>
          <span className="op-pd-spinner" aria-hidden="true" /> {lblChecking}
        </div>
      )}
      {verifyState === 'notfound' && !error && (
        <div className="badge badge-danger" style={{ alignSelf: 'flex-start', fontSize: '.95rem' }}>{lblNotFound}</div>
      )}
      {verifyState === 'offline' && (
        <div className="badge badge-warning" style={{ alignSelf: 'flex-start', fontSize: '.95rem' }}>{lblOffline}</div>
      )}
      {verifyNote && (
        <div className="muted" style={{ alignSelf: 'flex-start', fontSize: '.85rem' }}>{verifyNote}</div>
      )}

      {/* Action row per type */}
      {(type === 'textInput' || type === 'numberInput' || type === 'dateInput') && (
        <PrimaryButton
          label={checking ? lblChecking : 'Confirm'}
          disabled={disabled || checking}
          compact={compact}
          onClick={() => submit(type === 'numberInput' ? (num === '' ? '' : Number(num)) : type === 'dateInput' ? date : text)}
        />
      )}

      {type === 'acknowledge' && (
        <PrimaryButton
          label={config.confirmLabel || 'Continue'}
          disabled={disabled || (config.requireCheckbox === true && !ackChecked)}
          compact={compact}
          onClick={() => { setError(null); onSubmit(undefined) }}
        />
      )}

      {type === 'questionYesNo' && (
        <div style={{ display: 'flex', gap: '.75rem' }}>
          <PrimaryButton label="Yes" disabled={disabled} compact={compact} grow onClick={() => onSubmit(true)} />
          <GhostButton label="No" disabled={disabled} compact={compact} grow onClick={() => onSubmit(false)} />
        </div>
      )}

      {type === 'questionChoice' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '.6rem' }}>
          {(config.options ?? []).length === 0 ? (
            <span style={{ color: 'var(--text-dim)' }}>(no options configured)</span>
          ) : (
            (config.options ?? []).map((o) => (
              <GhostButton key={o.key} label={o.label || o.key} disabled={disabled} compact={compact} grow onClick={() => onSubmit(o.key)} />
            ))
          )}
        </div>
      )}

      {onBack && (
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          style={{ alignSelf: 'flex-start' }}
          disabled={disabled}
          onClick={onBack}
        >
          ← Back
        </button>
      )}
    </div>
  )
}

function PrimaryButton({ label, onClick, disabled, compact, grow }: { label: string; onClick: () => void; disabled?: boolean; compact?: boolean; grow?: boolean }) {
  return (
    <button
      type="button"
      className="btn btn-primary btn-lg"
      style={{ minHeight: compact ? 52 : 64, fontSize: compact ? '1.05rem' : '1.15rem', flex: grow ? '1 1 0' : undefined }}
      disabled={disabled}
      onClick={onClick}
    >
      {label}
    </button>
  )
}

function GhostButton({ label, onClick, disabled, compact, grow }: { label: string; onClick: () => void; disabled?: boolean; compact?: boolean; grow?: boolean }) {
  return (
    <button
      type="button"
      className="btn btn-ghost btn-lg"
      style={{ minHeight: compact ? 52 : 64, fontSize: compact ? '1.05rem' : '1.15rem', flex: grow ? '1 1 0' : undefined }}
      disabled={disabled}
      onClick={onClick}
    >
      {label}
    </button>
  )
}
