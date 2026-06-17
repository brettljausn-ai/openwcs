// The step-by-step "Verify" configuration dialog (replaces the old cramped inline block in the
// properties panel). A lightweight modal (the shared .modal-backdrop + .dialog CSS) walking the
// designer through three guided steps:
//   1. What does this screen scan?  -> pick the verify kind.
//   2. Store the resolved details   -> map ONLY the chosen kind's resolvable fields into variables.
//   3. If it's not found            -> re-prompt or jump to a step.
// The per-kind field list is the core fix: a location offers purpose/type/status, a SKU offers
// description/unit/category — driven by capabilities.verifyFields[kind] (with a built-in fallback).
//
// Rules of Hooks: every hook runs unconditionally at the top, BEFORE any early return.

import { useEffect, useMemo, useState } from 'react'
import { useT } from '../../i18n/useT'
import {
  verifyFieldsForKind,
  verifyWritePathsForKind,
  type Capabilities,
  type DataVar,
  type VerifyConfig,
  type VerifyKind,
} from '../model'
import VarCombobox from './VarCombobox'

/** English fallback labels for the server-driven verify kinds (the i18n key still overrides these). */
const VERIFY_KIND_LABELS: Record<string, string> = {
  barcode: 'Barcode',
  sku: 'SKU code',
  location: 'Location',
  skuScan: 'Scan SKU code or barcode',
  order: 'Scan an order / picksheet',
  asn: 'Scan an ASN',
}

/** Short per-kind guidance shown under the kind picker in step 1. */
const VERIFY_KIND_HELP: Record<string, string> = {
  barcode: 'The operator scans a product barcode. The unit of measure is pinned by the barcode.',
  sku: 'The operator scans or types a SKU code, resolved against your article master.',
  location: 'The operator scans or types a storage location, resolved against your location master.',
  skuScan: 'A barcode pins the unit of measure; a SKU code with several units prompts the operator to pick one.',
  order: 'Resolves an order or picksheet barcode against order management (e.g. for picking).',
  asn: 'Resolves an inbound ASN barcode against order management (e.g. for goods-in).',
}

interface Props {
  /** The verify config being edited (always defined while the dialog is open). */
  verify: VerifyConfig
  /** The verify kinds the server can resolve. */
  kinds: VerifyKind[]
  /** Server capabilities (drives the per-kind resolvable-field catalog). */
  capabilities: Capabilities
  /** Data-object variables to store resolved values into. */
  vars: DataVar[]
  /** Step ids selectable as the "not found -> go to step" target (excludes the current step). */
  stepIds: string[]
  /** Apply the edited config (Done). */
  onDone: (v: VerifyConfig) => void
  /** Discard and close (Cancel / Esc / click-outside). */
  onCancel: () => void
  /** Turn verification off for this step (only offered when it is already configured). */
  onRemove?: () => void
}

export default function VerifyDialog({ verify, kinds, capabilities, vars, stepIds, onDone, onCancel, onRemove }: Props) {
  const t = useT('processDesign')
  // Local draft so Cancel discards; Done commits.
  const [draft, setDraft] = useState<VerifyConfig>(verify)
  const [stepIdx, setStepIdx] = useState(0)

  // Esc closes the dialog (accessible, no focus trap required).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); onCancel() }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onCancel])

  const kindLabel = (k: string) => t(`verifyKind_${k}`, VERIFY_KIND_LABELS[k] ?? k)
  const kindHelp = (k: string) => t(`verifyKindHelp_${k}`, VERIFY_KIND_HELP[k] ?? '')

  // The resolvable fields for the chosen kind (server catalog, else built-in fallback).
  const fields = useMemo(
    () => verifyFieldsForKind(draft.kind, capabilities.verifyFields),
    [draft.kind, capabilities.verifyFields],
  )

  const setKind = (kind: VerifyKind) => {
    if (kind === draft.kind) return
    // Switching kind: drop any write mappings whose path is not valid for the new kind. Valid paths
    // include scalar keys, each object's whole key, and each object sub-field as `objectKey.subKey`.
    const validPaths = verifyWritePathsForKind(kind, capabilities.verifyFields)
    const write: Record<string, string> = {}
    for (const [k, v] of Object.entries(draft.write ?? {})) if (validPaths.has(k)) write[k] = v
    setDraft({ ...draft, kind, write })
  }

  const setWrite = (key: string, varName: string) => {
    const write = { ...(draft.write ?? {}) }
    if (varName) write[key] = varName
    else delete write[key]
    setDraft({ ...draft, write })
  }

  const setOnNotFound = (mode: 'reprompt' | 'goto', step?: string) => {
    setDraft({ ...draft, onNotFound: mode === 'goto' ? { mode: 'goto', step } : { mode: 'reprompt' } })
  }

  const steps = [
    t('verifyStep1', 'What does this screen scan?'),
    t('verifyStep2', 'Store the resolved details'),
    t('verifyStep3', "If it's not found"),
  ]

  return (
    <div className="modal-backdrop" onMouseDown={onCancel}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('verifyDialogTitle', 'Set up verification')}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginBottom: '.4rem' }}>{t('verifyDialogTitle', 'Set up verification')}</h2>
        <p className="muted" style={{ fontSize: '.8rem', margin: '0 0 1rem' }}>
          {t('verifyDialogIntro', 'Verification resolves the scanned value against master-data on submit. It needs connectivity.')}
        </p>

        {/* Step indicator */}
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
          {/* Step 1 — kind */}
          {stepIdx === 0 && (
            <>
              <h3 className="op-pd-verify-h">{steps[0]}</h3>
              <p className="muted op-pd-verify-guide">
                {t('verifyStep1Guide', 'Pick what the operator scans here. This decides which details can be resolved and stored.')}
              </p>
              <div className="op-pd-verify-kinds">
                {kinds.map((k) => (
                  <label key={k} className={`op-pd-verify-kind${draft.kind === k ? ' is-selected' : ''}`}>
                    <input
                      type="radio"
                      name="verify-kind"
                      checked={draft.kind === k}
                      onChange={() => setKind(k)}
                    />
                    <span className="op-pd-verify-kind-label">{kindLabel(k)}</span>
                    {kindHelp(k) && <span className="op-pd-verify-kind-help muted">{kindHelp(k)}</span>}
                  </label>
                ))}
              </div>
            </>
          )}

          {/* Step 2 — store resolved details (per-kind fields) */}
          {stepIdx === 1 && (
            <>
              <h3 className="op-pd-verify-h">{steps[1]}</h3>
              <p className="muted op-pd-verify-guide">
                {t('verifyStep2Guide', 'These are written into your data object for later steps. Leave a field on "do not store" to skip it.')}
              </p>
              {vars.length === 0 ? (
                <p className="muted" style={{ fontSize: '.8rem' }}>
                  {t('verifyNoVars', 'Add data-object variables (in the panel) to store resolved values.')}
                </p>
              ) : fields.length === 0 ? (
                <p className="muted" style={{ fontSize: '.8rem' }}>
                  {t('verifyNoFields', 'This kind has no resolvable details to store.')}
                </p>
              ) : (
                <div className="op-pd-verify-fields">
                  {fields.map((f) =>
                    f.object && f.sub && f.sub.length > 0 ? (
                      // Object field: a "store whole object" row plus an indented list of its
                      // properties. These are alternatives/additions (you can store the whole object
                      // AND/OR individual properties into different variables).
                      <div key={f.key} className="op-pd-verify-objgroup">
                        <div className="op-pd-verify-field-row op-pd-verify-objgroup-head">
                          <span className="op-pd-verify-field-label">
                            {t('verifyStoreWholeObject', '{label}: store whole object').replace('{label}', f.label)}
                          </span>
                          <div className="op-pd-verify-field-pick">
                            <VarCombobox
                              value={draft.write?.[f.key] ?? ''}
                              options={vars}
                              placeholder={t('verifyDontStore', '(do not store)')}
                              onChange={(name) => setWrite(f.key, name)}
                            />
                          </div>
                        </div>
                        <p className="muted op-pd-verify-objgroup-hint">
                          {t('verifyObjectHint', 'Store the whole object and/or pick out individual properties below.')}
                        </p>
                        <div className="op-pd-verify-objgroup-subs">
                          {f.sub.map((s) => {
                            const path = `${f.key}.${s.key}`
                            return (
                              <div key={path} className="op-pd-verify-field-row op-pd-verify-subrow">
                                <span className="op-pd-verify-field-label">{s.label}</span>
                                <div className="op-pd-verify-field-pick">
                                  <VarCombobox
                                    value={draft.write?.[path] ?? ''}
                                    options={vars}
                                    placeholder={t('verifyDontStore', '(do not store)')}
                                    onChange={(name) => setWrite(path, name)}
                                  />
                                </div>
                              </div>
                            )
                          })}
                        </div>
                      </div>
                    ) : (
                      <div key={f.key} className="op-pd-verify-field-row">
                        <span className="op-pd-verify-field-label">{f.label}</span>
                        <div className="op-pd-verify-field-pick">
                          <VarCombobox
                            value={draft.write?.[f.key] ?? ''}
                            options={vars}
                            placeholder={t('verifyDontStore', '(do not store)')}
                            onChange={(name) => setWrite(f.key, name)}
                          />
                        </div>
                      </div>
                    ),
                  )}
                </div>
              )}
            </>
          )}

          {/* Step 3 — not found */}
          {stepIdx === 2 && (
            <>
              <h3 className="op-pd-verify-h">{steps[2]}</h3>
              <p className="muted op-pd-verify-guide">
                {t('verifyStep3Guide', 'Choose what happens when the scanned value cannot be resolved.')}
              </p>
              <label className="op-pd-verify-radio">
                <input
                  type="radio"
                  name="verify-notfound"
                  checked={draft.onNotFound.mode === 'reprompt'}
                  onChange={() => setOnNotFound('reprompt')}
                />
                {t('verifyReprompt', 'Re-prompt the scan')}
              </label>
              <label className="op-pd-verify-radio">
                <input
                  type="radio"
                  name="verify-notfound"
                  checked={draft.onNotFound.mode === 'goto'}
                  onChange={() => setOnNotFound('goto', draft.onNotFound.step)}
                />
                {t('verifyGoto', 'Go to step')}
              </label>
              {draft.onNotFound.mode === 'goto' && (
                <label className="op-pd-field" style={{ marginTop: '.5rem' }}>
                  <span className="op-pd-field-label">{t('verifyGotoStep', 'Go to step')}</span>
                  <select
                    value={draft.onNotFound.step ?? ''}
                    onChange={(e) => setOnNotFound('goto', e.target.value || undefined)}
                  >
                    <option value="">{t('verifyPickStep', '(pick a step)')}</option>
                    {stepIds.map((s) => (<option key={s} value={s}>{s}</option>))}
                  </select>
                </label>
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
            {onRemove && (
              <button type="button" className="btn btn-ghost op-pd-remove" onClick={onRemove}>
                {t('verifyRemove', 'Turn off verification')}
              </button>
            )}
            <button type="button" className="btn btn-ghost" onClick={onCancel}>
              {t('cancel', 'Cancel')}
            </button>
            <button type="button" className="btn btn-primary" onClick={() => onDone(draft)}>
              {t('done', 'Done')}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
