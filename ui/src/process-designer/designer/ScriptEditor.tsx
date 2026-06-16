// Phase 3: the sandboxed-script step editor (spec §7.2). Shown in the properties panel when the
// selected task step's task type is SCRIPT_TASK_TYPE and the server capabilities allow scripting
// (scriptingEnabled && canAuthorScript). Deliberately a styled monospace <textarea> rather than a
// heavyweight code-editor dependency, to keep the bundle lean.
//
// The snippet runs server-side with a read-only `data` (the process data object) in scope and must
// return / assign an object whose fields become the declared outputs. The sandbox has no network,
// host or filesystem access and is CPU/time limited; the SERVER does the authoritative parse and
// limit enforcement — the designer only does light client-side checks (empty script, valid output
// identifiers) via validate.ts.
//
// Rules of Hooks: no hooks here (pure controlled inputs); the parent declares all hooks first.

import { useT } from '../../i18n/useT'
import type { ScriptConfig, ScriptOutput } from '../model'

const IDENT_RE = /^[A-Za-z_$][A-Za-z0-9_$]*$/

export default function ScriptEditor({
  config,
  onChange,
}: {
  config: ScriptConfig | undefined
  onChange: (config: ScriptConfig) => void
}) {
  const t = useT('processDesign')
  const cfg: ScriptConfig = config ?? { script: '', outputs: [] }
  const outputs = cfg.outputs ?? []

  const setScript = (script: string) => onChange({ ...cfg, script })
  const setOutputs = (next: ScriptOutput[]) => onChange({ ...cfg, outputs: next })

  return (
    <fieldset className="op-pd-fieldset op-pd-script">
      <legend>{t('scriptStep', 'Sandboxed script')}</legend>
      <p className="muted op-pd-script-help">
        {t(
          'scriptHelp',
          'The snippet has read-only data (the process data object) in scope and must return or assign an object whose fields become the declared outputs below. Sandbox limits apply: no network, host or filesystem access, and CPU/time are limited. The server does the final parse and enforcement.',
        )}
      </p>

      <label className="op-pd-field">
        <span className="op-pd-field-label">{t('scriptCode', 'Script (JavaScript)')}</span>
        <textarea
          className="op-pd-code"
          spellCheck={false}
          autoCorrect="off"
          autoCapitalize="off"
          rows={10}
          value={cfg.script ?? ''}
          placeholder={'// e.g.\nreturn { total: data.qty * data.price }'}
          onChange={(e) => setScript(e.target.value)}
        />
      </label>

      <div className="op-pd-script-outputs">
        <span className="op-pd-field-label">{t('scriptOutputs', 'Declared outputs')}</span>
        <p className="muted" style={{ fontSize: '.72rem', margin: '0 0 .4rem' }}>
          {t('scriptOutputsHint', 'Each field name the script returns. Map a matching data-object variable to keep the value.')}
        </p>
        {outputs.map((o, i) => {
          const name = (o.name ?? '').trim()
          const invalid = name !== '' && !IDENT_RE.test(name)
          return (
            <div key={i} style={{ display: 'flex', gap: '.4rem', marginBottom: '.4rem', alignItems: 'center' }}>
              <input
                placeholder={t('scriptOutputName', 'output name')}
                value={o.name}
                style={{ flex: 1 }}
                onChange={(e) => setOutputs(outputs.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))}
              />
              {invalid && <span className="op-pd-issue-err" style={{ fontSize: '.72rem' }} title={t('scriptOutputInvalid', 'Must be a valid identifier')}>⚠</span>}
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOutputs(outputs.filter((_, j) => j !== i))}>✕</button>
            </div>
          )
        })}
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOutputs([...outputs, { name: '' }])}>
          {t('scriptAddOutput', '+ Add output')}
        </button>
      </div>
    </fieldset>
  )
}
