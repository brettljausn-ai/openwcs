// Shared demo-mode awareness + the demo "Add orders / count task" seed calls. Used by the inbound,
// outbound and stock-counting screens to show their seed buttons only while demo mode is on.
import { useEffect, useState } from 'react'
import { getDemoStatus } from '../settings/api'
import { useWarehouse } from '../warehouse/WarehouseContext'

/** Whether demo mode is currently on for the active warehouse (and whether we are still checking). */
export function useDemoMode(): { enabled: boolean; loading: boolean } {
  const { currentWarehouseId } = useWarehouse()
  const [enabled, setEnabled] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    if (!currentWarehouseId) {
      setEnabled(false)
      setLoading(false)
      return
    }
    setLoading(true)
    getDemoStatus(currentWarehouseId)
      .then((s) => {
        if (!cancelled) setEnabled(s.enabled)
      })
      .catch(() => {
        if (!cancelled) setEnabled(false)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [currentWarehouseId])

  return { enabled, loading }
}

async function seedPost(url: string): Promise<{ created: number }> {
  const res = await fetch(url, { method: 'POST' })
  if (!res.ok) {
    const b = (await res.json().catch(() => ({}))) as { detail?: string }
    throw new Error(b.detail || `${res.status} ${res.statusText}`)
  }
  return (await res.json()) as { created: number }
}

/** Demo-only: bulk-create sample orders of a direction for the warehouse. */
export function seedDemoOrders(
  warehouseId: string,
  type: 'INBOUND' | 'OUTBOUND',
  count = 10,
): Promise<{ created: number }> {
  const wh = encodeURIComponent(warehouseId)
  return seedPost(`/api/orders/demo/seed?warehouseId=${wh}&type=${type}&count=${count}`)
}

/** Demo-only: create sample count tasks over existing demo stock (defaults to one). */
export function seedDemoCountTasks(warehouseId: string, count = 1): Promise<{ created: number }> {
  const wh = encodeURIComponent(warehouseId)
  return seedPost(`/api/counting/demo/seed?warehouseId=${wh}&count=${count}`)
}
