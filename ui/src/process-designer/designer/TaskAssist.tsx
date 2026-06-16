// Phase 3: the AI "describe the task" assist (spec §7.3) shown in the task-step properties when the
// server capabilities report aiAssistEnabled. The author types what the step should do; "Suggest"
// POSTs { description, variables } (variables = the current data-object schema) to /assist/task and
// renders the suggestion:
//   - kind "curated" → the chosen curated task type + proposed input/output mappings + rationale +
//     confidence, with Apply (sets the step's task type and fills the mappings).
//   - kind "script"  → the drafted snippet, read-only, clearly labelled "AI draft, for your review",
//     with "Insert as script step" (only when scripting is allowed; otherwise a disabled-with-note
//     state). Nothing is deployed.
//   - kind "none"    → just the rationale.
// 503 (AI not configured) is handled gracefully; loading + error states are explicit.
//
// This is design-time assistance only: it NEVER deploys generated code. The script draft is inserted
// into the step for the author (and later a developer) to review before publish.
//
// Rules of Hooks: all hooks are declared unconditionally at the top, before any early return.

import { useState } from 'react'
import { useT } from '../../i18n/useT'
import { assistTask } from '../api'
import type { AssistSuggestion, AssistVariable, DataVar, ScriptConfig } from '../model'

interface Props {
  /** The current definition's data-object schema (sent as the assist `variables`). */
  schema: DataVar[]
  /** Whether a drafted script can actually be inserted (scriptingEnabled && canAuthorScript). */
  canInsertScript: boolean
  /** Apply a curated suggestion: switch the step to `taskType` and fill the input/output mappings. */
  onApplyCurated: (taskType: string, inputs: Record<string, string>, outputs: Record<string, string>) => void
  /** Insert the drafted snippet as a script step (sets task type = script + the config). */
  onInsertScript: (config: ScriptConfig) => void
}

export default function TaskAssist({ schema, canInsertScript, onApplyCurated, onInsertScript }: Props) {
  const t = useT('processDesign')
  const [description, setDescription] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notConfigured, setNotConfigured] = useState(false)
  const [suggestion, setSuggestion] = useState<AssistSuggestion | null>(null)

  const suggest = async () => {
    if (!description.trim()) return
    setLoading(true)
    setError(null)
    setNotConfigured(false)
    setSuggestion(null)
    try {
      const variables: AssistVariable[] = schema.map((v) => ({ name: v.name, type: v.type }))
      const result = await assistTask(description.trim(), variables)
      setSuggestion(result)
    } catch (e) {
      const err = e as Error & { httpStatus?: number }
      if (err.httpStatus === 503) setNotConfigured(true)
      else setError(err instanceof Error ? err.message : String(err))
    } finally {
      setLoading(false)
    }
  }

  return (
    <fieldset className="op-pd-fieldset op-pd-assist">
      <legend>{t('assist', 'AI assist')}</legend>
      <p className="muted" style={{ fontSize: '.74rem', margin: '0 0 .5rem' }}>
        {t('assistHint', 'Describe what this step should do; the assistant suggests a curated task (or drafts a snippet for review). Nothing is deployed.')}
      </p>

      <textarea
        className="op-pd-assist-input"
        rows={2}
        value={description}
        placeholder={t('assistPlaceholder', 'e.g. put the scanned tote away into the best location')}
        onChange={(e) => setDescription(e.target.value)}
      />
      <button
        type="button"
        className="btn btn-primary btn-sm"
        style={{ marginTop: '.4rem' }}
        disabled={loading || !description.trim()}
        onClick={() => void suggest()}
      >
        {loading ? t('assistLoading', 'Thinking…') : t('assistSuggest', 'Suggest')}
      </button>

      {notConfigured && (
        <p className="muted op-pd-assist-note" style={{ marginTop: '.5rem' }}>
          {t('assistNotConfigured', 'AI assist is not configured.')}
        </p>
      )}
      {error && (
        <p className="op-pd-issue-err" style={{ fontSize: '.78rem', marginTop: '.5rem' }}>⚠ {error}</p>
      )}

      {suggestion && (
        <Suggestion
          suggestion={suggestion}
          canInsertScript={canInsertScript}
          onApplyCurated={onApplyCurated}
          onInsertScript={onInsertScript}
        />
      )}
    </fieldset>
  )
}

function Confidence({ value }: { value?: number }) {
  const t = useT('processDesign')
  if (value == null) return null
  const pct = Math.round(Math.max(0, Math.min(1, value)) * 100)
  return <span className="op-pd-assist-conf">{t('assistConfidence', 'Confidence')}: {pct}%</span>
}

function Suggestion({
  suggestion,
  canInsertScript,
  onApplyCurated,
  onInsertScript,
}: {
  suggestion: AssistSuggestion
  canInsertScript: boolean
  onApplyCurated: Props['onApplyCurated']
  onInsertScript: Props['onInsertScript']
}) {
  const t = useT('processDesign')
  const { kind, rationale } = suggestion

  if (kind === 'curated') {
    const inputs = suggestion.inputs ?? {}
    const outputs = suggestion.outputs ?? {}
    return (
      <div className="op-pd-assist-result">
        <div className="op-pd-assist-head">
          <span className="op-pd-assist-kind">{t('assistCurated', 'Suggested task')}</span>
          <Confidence value={suggestion.confidence} />
        </div>
        <p className="op-pd-assist-tasktype"><strong>{suggestion.taskType ?? '?'}</strong></p>
        {Object.keys(inputs).length > 0 && (
          <div className="op-pd-assist-maps">
            <span className="muted">{t('assistInputs', 'Inputs')}</span>
            <ul>{Object.entries(inputs).map(([k, v]) => (<li key={k}><code>{k}</code> ← <code>{v}</code></li>))}</ul>
          </div>
        )}
        {Object.keys(outputs).length > 0 && (
          <div className="op-pd-assist-maps">
            <span className="muted">{t('assistOutputs', 'Outputs')}</span>
            <ul>{Object.entries(outputs).map(([k, v]) => (<li key={k}><code>{k}</code> → <code>{v}</code></li>))}</ul>
          </div>
        )}
        {rationale && <p className="muted op-pd-assist-rationale">{rationale}</p>}
        <button
          type="button"
          className="btn btn-primary btn-sm"
          disabled={!suggestion.taskType}
          onClick={() => suggestion.taskType && onApplyCurated(suggestion.taskType, inputs, outputs)}
        >
          {t('assistApply', 'Apply')}
        </button>
      </div>
    )
  }

  if (kind === 'script') {
    const script = suggestion.script ?? ''
    return (
      <div className="op-pd-assist-result">
        <div className="op-pd-assist-head">
          <span className="op-pd-assist-kind op-pd-assist-draft">{t('assistDraftLabel', 'AI draft, for your review')}</span>
          <Confidence value={suggestion.confidence} />
        </div>
        <textarea className="op-pd-code op-pd-assist-draft-code" readOnly rows={8} value={script} />
        {rationale && <p className="muted op-pd-assist-rationale">{rationale}</p>}
        <p className="muted" style={{ fontSize: '.72rem' }}>{t('assistNotDeployed', 'Nothing has been deployed. Review before publishing.')}</p>
        {canInsertScript ? (
          <button
            type="button"
            className="btn btn-primary btn-sm"
            disabled={!script.trim()}
            onClick={() => onInsertScript({ script, outputs: [] })}
          >
            {t('assistInsertScript', 'Insert as script step')}
          </button>
        ) : (
          <p className="muted op-pd-assist-note">{t('assistScriptDisabled', 'Scripting is disabled, so this draft cannot be inserted.')}</p>
        )}
      </div>
    )
  }

  // kind === 'none'
  return (
    <div className="op-pd-assist-result">
      <div className="op-pd-assist-head">
        <span className="op-pd-assist-kind">{t('assistNone', 'No applicable task')}</span>
        <Confidence value={suggestion.confidence} />
      </div>
      {rationale && <p className="muted op-pd-assist-rationale">{rationale}</p>}
    </div>
  )
}
