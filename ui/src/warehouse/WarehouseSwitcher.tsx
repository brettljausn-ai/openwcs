import Select from '../ui/Select'
import { useWarehouse } from './WarehouseContext'
import { useT } from '../i18n/useT'

// Global warehouse picker for the app top bar. Lists only the warehouses the user is allowed
// into; switching here drives every warehouse-scoped screen. No UUIDs are ever shown or typed.
export default function WarehouseSwitcher() {
  const { loading, warehouses, currentWarehouseId, setCurrentWarehouseId, defaultWarehouseId } = useWarehouse()
  const t = useT('common')

  if (loading) {
    return <span className="warehouse-switcher muted">{t('loadingWarehouses', 'Loading warehouses…')}</span>
  }
  if (warehouses.length === 0) {
    return <span className="warehouse-switcher muted">{t('noWarehouseAccess', 'No warehouse access')}</span>
  }

  return (
    <label className="warehouse-switcher">
      <span className="warehouse-switcher-label">{t('warehouse', 'Warehouse')}</span>
      <Select
        ariaLabel="Warehouse"
        value={currentWarehouseId}
        onChange={setCurrentWarehouseId}
        options={warehouses.map((w) => ({
          value: w.id,
          label: `${w.code} — ${w.name}${w.id === defaultWarehouseId ? ' (default)' : ''}`,
        }))}
      />
    </label>
  )
}
