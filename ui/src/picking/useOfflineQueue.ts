// React glue for the durable offline confirm queue: subscribes a component to the queue contents
// and to the browser's online/offline state, and starts the background drainer once. Used by the
// picking screen's offline banner / status indicator.
import { useEffect, useState } from 'react'
import {
  drainQueue,
  startQueueDrainer,
  subscribe,
  type QueuedConfirm,
} from './offlineQueue'

export interface OfflineQueueState {
  items: QueuedConfirm[]
  pending: number
  failed: number
  online: boolean
  retryNow: () => void
}

export function useOfflineQueue(): OfflineQueueState {
  const [items, setItems] = useState<QueuedConfirm[]>([])
  const [online, setOnline] = useState<boolean>(() =>
    typeof navigator === 'undefined' ? true : navigator.onLine,
  )

  useEffect(() => {
    startQueueDrainer()
    const unsub = subscribe(setItems)
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => {
      unsub()
      window.removeEventListener('online', on)
      window.removeEventListener('offline', off)
    }
  }, [])

  return {
    items,
    pending: items.filter((i) => !i.failed).length,
    failed: items.filter((i) => i.failed).length,
    online,
    retryNow: () => void drainQueue(),
  }
}
