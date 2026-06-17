// The "Data object" editor lifted out of the properties panel into its own modal (reuses the shared
// .modal-backdrop + .dialog CSS, like VerifyDialog / TaskDialog). It edits the typed variables the
// process reads and writes: a list of name + type rows with delete, plus "+ Add variable". Opened from
// the "Data object" button in the left flow pane so the properties panel stays focused on the selected
// step.
//
// Rules of Hooks: the only hook (Esc handler) runs unconditionally at the top, before any return.

import { useEffect } from 'react'
import { useT } from '../../i18n/useT'
import type { DataVar, VarType } from '../model'

const VAR_TYPES: VarType[] = ['string', 'number', 'boolean', 'date', 'sku', 'location', 'hu']

interface Props {
  schema: DataVar[]
  onChange: (schema: DataVar[]) => void
  onClose: () => void
}

export default function DataObjectDialog({ schema, onChange, onClose }: Props) {
  const t = useT('processDesign')

  // Esc closes the dialog.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); onClose() }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-label={t('dataObject', 'Data object')}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <h2 style={{ marginBottom: '.4rem' }}>{t('dataObject', 'Data object')}</h2>
        <p className="muted" style={{ fontSize: '.8rem', margin: '0 0 1rem' }}>
          {t('dataObjectIntro', 'The typed variables your process reads and writes.')}
        </p>

        <fieldset className="op-pd-fieldset op-pd-dataobj">
          {schema.map((v, i) => (
            <div key={i} className="op-pd-dataobj-row">
              <input
                className="op-pd-dataobj-name"
                value={v.name}
                placeholder={t('varName', 'name')}
                onChange={(e) => onChange(schema.map((x, j) => (j === i ? { ...x, name: e.target.value.replace(/[^A-Za-z0-9_]/g, '') } : x)))}
              />
              <select
                className="op-pd-dataobj-type"
                value={v.type}
                onChange={(e) => onChange(schema.map((x, j) => (j === i ? { ...x, type: e.target.value as VarType } : x)))}
              >
                {VAR_TYPES.map((ty) => (<option key={ty} value={ty}>{ty}</option>))}
              </select>
              <button
                type="button"
                className="btn btn-ghost btn-sm op-pd-dataobj-del"
                title={t('delete', 'Delete')}
                onClick={() => onChange(schema.filter((_, j) => j !== i))}
              >
                ✕
              </button>
            </div>
          ))}
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => onChange([...schema, { name: `var${schema.length + 1}`, type: 'string' }])}
          >
            {t('addVariable', '+ Add variable')}
          </button>
        </fieldset>

        <div className="dialog-actions">
          <button type="button" className="btn btn-primary" onClick={onClose}>{t('done', 'Done')}</button>
        </div>
      </div>
    </div>
  )
}
