// The step-by-step "Edit screen" configuration dialog (replaces the old cramped inline screen block
// in the properties panel, mirroring the Task and Verify dialogs). A lightweight modal (the shared
// .modal-backdrop + .dialog CSS) walking the designer through two guided steps:
//   1. Basics    -> the step name (renames the step id), the Header and Detail copy (with the
//                   {{var}} placeholder insert chips).
//   2. Capture & validation -> only the parts relevant to THIS screen type:
//        - text/number: Write to (variable), Scan binding toggle, the Validation builder.
//        - date:        Write to (variable), the Validation builder (min/max date).
//        - questionYesNo / questionChoice: the "writes to" variable; questionChoice also the Answers.
//        - acknowledge: the confirm-button label + an optional confirmation checkbox + its label.
//
// It edits a LOCAL draft (so Cancel / Esc / click-outside discard) and commits on Done via onDone.
// The step name renames live through onRename (like TaskDialog) so references update immediately.
//
// Rules of Hooks: every hook runs unconditionally at the top, BEFORE any early return.

import { useEffect, useState } from 'react'
import { useT } from '../../i18n/useT'
import type { ChoiceOption, DataVar, ScreenStep } from '../model'
import VarCombobox from './VarCombobox'

interface Props {
  /** The screen step being edited. The dialog edits a local draft of its config. */
  step: ScreenStep
  /** Data-object variables for placeholders + the write-to / answer pickers. */
  vars: DataVar[]
  /** The current id (name) of the step; shown + renamable at the top of the dialog. */
  stepId: string
  /** All step ids (to reject a rename that would collide with an existing id). */
  stepIds: string[]
  /** Rename the step id (updates references); called when the name field commits. */
  onRename: (oldId: string, newId: string) => void
  /** Apply the edited step (Done). */
  onDone: (s: ScreenStep) => void
  /** Discard and close (Cancel / Esc / click-outside). */
  onCancel: () => void
}

export default function ScreenDialog({ step, vars, stepId, stepIds, onRename, onDone, onCancel }: Props) {
  const t = useT('processDesign')
  // Local draft so Cancel discards; Done commits.
  const [draft, setDraft] = useState<ScreenStep>(step)
  const [stepIdx, setStepIdx] = useState(0)
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

  const cfg = draft.config
  const setCfg = (patch: Partial<typeof cfg>) => setDraft({ ...draft, config: { ...cfg, ...patch } })
  const setVal = (patch: Partial<NonNullable<typeof cfg.validation>>) =>
    setCfg({ validation: { ...cfg.validation, ...patch } })

  const isText = draft.screen === 'textInput'
  const isNum = draft.screen === 'numberInput'
  const isDate = draft.screen === 'dateInput'
  const isChoice = draft.screen === 'questionChoice'
  const isQuestion = draft.screen === 'questionYesNo' || isChoice
  const isAck = draft.screen === 'acknowledge'

  const steps = [
    t('screenDialogStep1', 'Basics'),
    t('screenDialogStep2', 'Capture & validation'),
  ]

  return (
    <div className="modal-backdrop" onMouseDown={onCancel}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('screenDialogTitle', 'Edit this screen')}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginBottom: '.4rem' }}>{t('screenDialogTitle', 'Edit this screen')}</h2>
        <p className="muted" style={{ fontSize: '.8rem', margin: '0 0 1rem' }}>
          {t('screenDialogIntro', 'Name the screen, write its copy, then set how it captures and validates. The flow is drawn on the canvas.')}
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

        {/* Step indicator (reuses the verify-dialog styling) */}
        <ol className="op-pd-verify-steps">
          {steps.map((label, i) => (
            <li
              key={i}
              className={`op-pd-verify-step${i === stepIdx ? ' is-active' : ''}${i < stepIdx ? ' is-done' : ''}`}
            >
              <button type="button" onClick={() => setStepIdx(i)}>
                <span className="op-pd-verify-step-num">{i + 1}</span>
                <span className="op-pd-verify-step-label">{label}</span>
              </button>
            </li>
          ))}
        </ol>

        <div className="op-pd-verify-pane">
          {stepIdx === 0 && (
            <>
              <h3 className="op-pd-verify-h">{steps[0]}</h3>
              <p className="muted op-pd-verify-guide">
                {t('screenDialogStep1Guide', 'The header and detail are what the operator reads. Use the chips to insert data-object values.')}
              </p>
              <TextWithPlaceholders label={t('screenHeader', 'Header')} value={cfg.header ?? ''} vars={vars} onChange={(v) => setCfg({ header: v })} />
              <TextWithPlaceholders label={t('screenDetail', 'Detail')} value={cfg.detail ?? ''} vars={vars} onChange={(v) => setCfg({ detail: v })} />
            </>
          )}

          {stepIdx === 1 && (
            <>
              <h3 className="op-pd-verify-h">{steps[1]}</h3>

              {(isText || isNum || isDate) && (
                <Field label={t('screenWriteTo', 'Write to (variable)')}>
                  <VarCombobox value={cfg.writeTo ?? ''} options={vars} onChange={(name) => setCfg({ writeTo: name || undefined })} />
                </Field>
              )}

              {(isText || isNum) && (
                <label className="op-pd-toggle">
                  <input type="checkbox" checked={!!cfg.scanBinding} onChange={(e) => setCfg({ scanBinding: e.target.checked })} />
                  {t('screenScanBinding', 'Scan binding (capture from a scanner)')}
                </label>
              )}

              {(isText || isNum || isDate) && (
                <fieldset className="op-pd-fieldset">
                  <legend>{t('screenValidation', 'Validation')}</legend>
                  <label className="op-pd-toggle">
                    <input type="checkbox" checked={!!cfg.validation?.required} onChange={(e) => setVal({ required: e.target.checked })} />
                    {t('screenRequired', 'Required')}
                  </label>
                  {isText && (
                    <>
                      <Field label={t('screenRegex', 'Regex (optional)')}>
                        <input value={cfg.validation?.regex ?? ''} onChange={(e) => setVal({ regex: e.target.value || undefined })} />
                      </Field>
                      <Field label={t('screenMaxLength', 'Max length')}>
                        <input type="number" value={cfg.validation?.maxLength ?? ''} onChange={(e) => setVal({ maxLength: e.target.value ? Number(e.target.value) : undefined })} />
                      </Field>
                      <Field label={t('screenMustEqual', 'Must equal ({{var}})')}>
                        <input value={cfg.validation?.mustEqual ?? ''} onChange={(e) => setVal({ mustEqual: e.target.value || undefined })} />
                      </Field>
                    </>
                  )}
                  {isNum && (
                    <>
                      <Field label={t('screenMin', 'Min')}><input type="number" value={cfg.validation?.min ?? ''} onChange={(e) => setVal({ min: e.target.value ? Number(e.target.value) : undefined })} /></Field>
                      <Field label={t('screenMax', 'Max')}><input type="number" value={cfg.validation?.max ?? ''} onChange={(e) => setVal({ max: e.target.value ? Number(e.target.value) : undefined })} /></Field>
                      <label className="op-pd-toggle">
                        <input type="checkbox" checked={!!cfg.validation?.integerOnly} onChange={(e) => setVal({ integerOnly: e.target.checked })} />
                        {t('screenIntegerOnly', 'Whole numbers only')}
                      </label>
                      <Field label={t('screenMustEqual', 'Must equal ({{var}})')}><input value={cfg.validation?.mustEqual ?? ''} onChange={(e) => setVal({ mustEqual: e.target.value || undefined })} /></Field>
                    </>
                  )}
                  {isDate && (
                    <>
                      <Field label={t('screenMinDate', 'Min date')}><input type="date" value={cfg.validation?.min != null ? String(cfg.validation.min) : ''} onChange={(e) => setVal({ min: e.target.value as unknown as number })} /></Field>
                      <Field label={t('screenMaxDate', 'Max date')}><input type="date" value={cfg.validation?.max != null ? String(cfg.validation.max) : ''} onChange={(e) => setVal({ max: e.target.value as unknown as number })} /></Field>
                    </>
                  )}
                </fieldset>
              )}

              {isQuestion && (
                <Field label={t('screenQuestionWriteTo', 'Question writes to (variable)')}>
                  <VarCombobox value={cfg.writeTo ?? ''} options={vars} onChange={(name) => setCfg({ writeTo: name || undefined })} />
                </Field>
              )}

              {isChoice && <ChoiceOptionsEditor options={cfg.options ?? []} onChange={(options) => setCfg({ options })} />}

              {isAck && (
                <>
                  <Field label={t('screenConfirmLabel', 'Confirm button label')}><input value={cfg.confirmLabel ?? ''} onChange={(e) => setCfg({ confirmLabel: e.target.value || undefined })} placeholder={t('screenConfirmPlaceholder', 'Continue')} /></Field>
                  <label className="op-pd-toggle">
                    <input type="checkbox" checked={!!cfg.requireCheckbox} onChange={(e) => setCfg({ requireCheckbox: e.target.checked })} />
                    {t('screenRequireCheckbox', 'Require a confirmation checkbox')}
                  </label>
                  {cfg.requireCheckbox && (
                    <Field label={t('screenCheckboxLabel', 'Checkbox label')}><input value={cfg.checkboxLabel ?? ''} onChange={(e) => setCfg({ checkboxLabel: e.target.value || undefined })} placeholder={t('screenCheckboxPlaceholder', 'I confirm')} /></Field>
                  )}
                </>
              )}
            </>
          )}
        </div>

        <div className="dialog-actions" style={{ justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', gap: '.5rem' }}>
            <button
              type="button"
              className="btn btn-ghost"
              disabled={stepIdx === 0}
              onClick={() => setStepIdx((i) => Math.max(0, i - 1))}
            >
              {t('verifyBack', 'Back')}
            </button>
            {stepIdx < steps.length - 1 && (
              <button type="button" className="btn btn-ghost" onClick={() => setStepIdx((i) => Math.min(steps.length - 1, i + 1))}>
                {t('verifyNext', 'Next')}
              </button>
            )}
          </div>
          <div style={{ display: 'flex', gap: '.5rem' }}>
            <button type="button" className="btn btn-ghost" onClick={onCancel}>{t('cancel', 'Cancel')}</button>
            <button type="button" className="btn btn-primary" onClick={() => onDone(draft)}>{t('done', 'Done')}</button>
          </div>
        </div>
      </div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="op-pd-field">
      <span className="op-pd-field-label">{label}</span>
      {children}
    </label>
  )
}

// --- header/detail with placeholder picker -------------------------------------------------------

function TextWithPlaceholders({
  label,
  value,
  vars,
  onChange,
}: {
  label: string
  value: string
  vars: DataVar[]
  onChange: (v: string) => void
}) {
  const t = useT('processDesign')
  return (
    <label className="op-pd-field op-pd-wide">
      <span className="op-pd-field-label">{label}</span>
      <textarea value={value} rows={2} onChange={(e) => onChange(e.target.value)} />
      {vars.length > 0 && (
        <div className="op-pd-chips">
          <span className="muted" style={{ fontSize: '.75rem' }}>{t('screenInsert', 'Insert:')}</span>
          {vars.map((v) => (
            <button
              key={v.name}
              type="button"
              className="op-pd-chip"
              onClick={() => onChange(`${value}{{${v.name}}}`)}
              title={`${v.name} (${v.type})`}
            >
              {`{{${v.name}}}`}
            </button>
          ))}
        </div>
      )}
    </label>
  )
}

// --- choice answers editor -----------------------------------------------------------------------

function ChoiceOptionsEditor({ options, onChange }: { options: ChoiceOption[]; onChange: (o: ChoiceOption[]) => void }) {
  const t = useT('processDesign')
  return (
    <fieldset className="op-pd-fieldset op-pd-wide">
      <legend>{t('screenAnswers', 'Answers')}</legend>
      {options.map((o, i) => (
        <div key={i} style={{ display: 'flex', gap: '.4rem', marginBottom: '.4rem' }}>
          <input placeholder={t('screenAnswerKey', 'key')} value={o.key} style={{ width: 90 }} onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))} />
          <input placeholder={t('screenAnswerLabel', 'label')} value={o.label} onChange={(e) => onChange(options.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))} />
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange(options.filter((_, j) => j !== i))}>✕</button>
        </div>
      ))}
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange([...options, { key: `opt${options.length + 1}`, label: '' }])}>{t('screenAddAnswer', '+ Add answer')}</button>
    </fieldset>
  )
}
