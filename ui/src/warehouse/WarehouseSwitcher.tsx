import { useWarehouse } from './WarehouseContext'

// Global warehouse picker for the app top bar. Lists only the warehouses the user is allowed
// into; switching here drives every warehouse-scoped screen. No UUIDs are ever shown or typed.
export default function WarehouseSwitcher() {
  const { loading, warehouses, currentWarehouseId, setCurrentWarehouseId, defaultWarehouseId } = useWarehouse()

  if (loading) {
    return <span className="warehouse-switcher muted">Loading warehouses…</span>
  }
  if (warehouses.length === 0) {
    return <span className="warehouse-switcher muted">No warehouse access</span>
  }

  return (
    <label className="warehouse-switcher">
      <span className="warehouse-switcher-label">Warehouse</span>
      <select
        className="form-control"
        value={currentWarehouseId}
        onChange={(e) => setCurrentWarehouseId(e.target.value)}
      >
        {warehouses.map((w) => (
          <option key={w.id} value={w.id}>
            {w.code} — {w.name}
            {w.id === defaultWarehouseId ? ' (default)' : ''}
          </option>
        ))}
      </select>
    </label>
  )
}
