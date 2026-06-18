// The "Edit decision" dialog (mirrors TaskDialog / ScreenDialog). A decision step is a no-op router
// (if / elseif / else): it renders no screen and makes no server call — the runtime evaluates its
// ordered RULES (the `transitions`, first matching `when` over the data object wins) and routes
// straight through to the resolved target, falling back to the else/default target (`next`).
//
// The dialog authors:
//   - the step name (id) at the top (renamed live via onRename, like TaskDialog),
//   - an ordered list of RULE rows: a condition input (`when`) + a target-step picker (`to`), with
//     add / remove / reorder; the condition is validated live (validateCondition +
//     unknownExpressionVars over the data schema, inline error). Order = priority (first match wins).
//   - an "Otherwise / else go to" target-step picker that maps to `next`.
//
// It edits a LOCAL draft (so Cancel / Esc / click-outside discard) and commits on Done via onDone.
//
// Rules of Hooks: every hook runs unconditionally at the top, BEFORE any early return.

import { useEffect, useMemo, useState } from 'react'
import { useT } from '../../i18n/useT'
import { unknownExpressionVars, validateCondition } from '../condition'
import type { DataVar, DecisionStep, Transition } from '../model'

interface Props {
  /** The decision step being edited. The dialog edits a local draft of its transitions + next. */
  step: DecisionStep
  /** Data-object variables for live condition validation. */
  vars: DataVar[]
  /** The current id (name) of the step; shown + renamable at the top of the dialog. */
  stepId: string
  /** All step ids (to reject a rename that would collide + to populate the target-step pickers). */
  stepIds: string[]
  /** Rename the step id (updates references); called when the name field commits. */
  onRename: (oldId: string, newId: string) => void
  /** Apply the edited step (Done). */
  onDone: (s: DecisionStep) => void
  /** Discard and close (Cancel / Esc / click-outside). */
  onCancel: () => void
}

export default function DecisionDialog({ step, vars, stepId, stepIds, onRename, onDone, onCancel }: Props) {
  const t = useT('processDesign')
  // Local draft so Cancel discards; Done commits.
  const [draft, setDraft] = useState<DecisionStep>(step)
  // The step name (id). Sanitised to [A-Za-z0-9_]; committing (onBlur / Enter) renames through the
  // parent so every `next`/branch reference updates. A blank or colliding name is rejected (reverts).
  const [nameDraft, setNameDraft] = useState(stepId)
  const commitName = () => {
    const next = nameDraft
    if (!next || next === stepId) { setNameDraft(stepId); return }
    if (stepIds.includes(next)) { setNameDraft(stepId); return }
    onRename(stepId, next)
  }

  // Esc closes the dialog.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); onCancel() }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onCancel])

  // The other step ids are the valid routing targets (a decision should not point at itself).
  const targets = useMemo(() => stepIds.filter((id) => id !== stepId), [stepIds, stepId])
  const varNames = useMemo(() => vars.map((v) => v.name), [vars])

  const rules = draft.transitions ?? []
  const setRules = (next: Transition[]) => setDraft({ ...draft, transitions: next.length ? next : undefined })
  const updateRule = (i: number, patch: Partial<Transition>) =>
    setRules(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const removeRule = (i: number) => setRules(rules.filter((_, j) => j !== i))
  const moveRule = (i: number, dir: -1 | 1) => {
    const j = i + dir
    if (j < 0 || j >= rules.length) return
    const next = rules.slice()
    ;[next[i], next[j]] = [next[j], next[i]]
    setRules(next)
  }
  const addRule = () => setRules([...rules, { when: '', to: targets[0] ?? '' }])
  const setElse = (id: string) => setDraft({ ...draft, next: id || undefined })

  return (
    <div className="modal-backdrop" onMouseDown={onCancel}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('decisionDialogTitle', 'Edit decision')}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginBottom: '.4rem' }}>{t('decisionDialogTitle', 'Edit decision')}</h2>
        <p className="muted" style={{ fontSize: '.8rem', margin: '0 0 1rem' }}>
          {t('decisionDialogIntro', 'A decision routes to a different next step depending on the data. It shows no screen and runs on the device.')}
        </p>

        {/* Step name (id): renames the step and updates every reference on commit. */}
        <label className="op-pd-field" style={{ marginBottom: '1rem' }}>
          <span className="op-pd-field-label">{t('stepName', 'Step name')}</span>
          <input
            value={nameDraft}
            onChange={(e) => setNameDraft(e.target.value.replace(/[^A-Za-z0-9_]/g, ''))}
            onBlur={commitName}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); commitName() } }}
            placeholder={t('stepNamePlaceholder', 'step name')}
          />
        </label>

        <div className="op-pd-verify-pane">
          <h3 className="op-pd-verify-h">{t('decisionRules', 'Rules')}</h3>
          <p className="muted op-pd-verify-guide">
            {t('decisionGuide', 'Rules are checked top to bottom; the first matching one wins, otherwise the else target is used.')}
          </p>

          <div className="op-pd-compute-rows">
            {rules.map((rule, i) => {
              const when = rule.when ?? ''
              const err = when.trim() ? validateCondition(when) : null
              const unknownVars = !err && when.trim() ? unknownExpressionVars(when, varNames) : []
              const targetMissing = !!rule.to && !targets.includes(rule.to)
              return (
                <div key={i} className="op-pd-compute-row">
                  <div className="op-pd-compute-row-main">
                    <span className="op-pd-decision-kw" aria-hidden="true">{i === 0 ? t('decisionIf', 'if') : t('decisionElseIf', 'else if')}</span>
                    <input
                      className="op-pd-compute-expr"
                      value={when}
                      spellCheck={false}
                      autoCorrect="off"
                      autoCapitalize="off"
                      placeholder={t('decisionCondPlaceholder', 'e.g. qty == 0')}
                      onChange={(e) => updateRule(i, { when: e.target.value })}
                    />
                    <span className="op-pd-compute-eq" aria-hidden="true">→</span>
                    <select
                      className="op-pd-decision-to"
                      value={rule.to}
                      onChange={(e) => updateRule(i, { to: e.target.value })}
                    >
                      <option value="">{t('decisionPickStep', 'go to step…')}</option>
                      {targets.map((id) => (
                        <option key={id} value={id}>{id}</option>
                      ))}
                      {targetMissing && <option value={rule.to}>{rule.to}</option>}
                    </select>
                    <div className="op-pd-compute-row-actions">
                      <button type="button" className="op-pd-mini" title={t('moveUp', 'Move up')} disabled={i === 0} onClick={() => moveRule(i, -1)}>↑</button>
                      <button type="button" className="op-pd-mini" title={t('moveDown', 'Move down')} disabled={i === rules.length - 1} onClick={() => moveRule(i, 1)}>↓</button>
                      <button type="button" className="op-pd-mini" title={t('delete', 'Delete')} onClick={() => removeRule(i)}>✕</button>
                    </div>
                  </div>
                  {!rule.to && <span className="op-pd-issue-err" style={{ fontSize: '.74rem' }}>⚠ {t('decisionNoTarget', 'Pick a step this rule goes to.')}</span>}
                  {targetMissing && <span className="op-pd-issue-err" style={{ fontSize: '.74rem' }}>⚠ {t('decisionTargetMissing', 'That target step no longer exists.')}</span>}
                  {err && <span className="op-pd-issue-err" style={{ fontSize: '.74rem' }}>⚠ {err}</span>}
                  {unknownVars.length > 0 && <span className="op-pd-issue-err" style={{ fontSize: '.74rem' }}>⚠ {t('computeUnknownVars', 'Unknown variable(s): {list}. Add them in Data object.').replace('{list}', unknownVars.join(', '))}</span>}
                </div>
              )
            })}
            {rules.length === 0 && (
              <p className="muted" style={{ fontSize: '.78rem', margin: '.2rem 0' }}>{t('decisionNoRules', 'No rules yet. Every instance takes the else target below.')}</p>
            )}
          </div>
          <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: '.4rem' }} onClick={addRule}>
            {t('decisionAddRule', '+ Add rule')}
          </button>

          <fieldset className="op-pd-fieldset op-pd-wide" style={{ marginTop: '.8rem' }}>
            <legend>{t('decisionElse', 'Otherwise / else go to')}</legend>
            <select
              className="op-pd-decision-to"
              value={draft.next ?? ''}
              onChange={(e) => setElse(e.target.value)}
            >
              <option value="">{t('decisionElseEnd', '(end the process)')}</option>
              {targets.map((id) => (
                <option key={id} value={id}>{id}</option>
              ))}
              {draft.next && !targets.includes(draft.next) && <option value={draft.next}>{draft.next}</option>}
            </select>
          </fieldset>
        </div>

        <div className="dialog-actions" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-ghost" onClick={onCancel}>{t('cancel', 'Cancel')}</button>
          <button type="button" className="btn btn-primary" onClick={() => onDone(draft)}>{t('done', 'Done')}</button>
        </div>
      </div>
    </div>
  )
}
