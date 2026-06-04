import { createContext, useContext, useEffect, useMemo, useState, ReactNode } from 'react'
import { useAuth } from '../auth/AuthContext'

// A warehouse the signed-in user may work in. Names come from master-data; the allowed set
// and default come from IAM (/api/iam/warehouse-access/me). The user never types a UUID — they
// pick from this list in the top-bar switcher, and their default is selected on login.
export interface WarehouseOption {
  id: string
  code: string
  name: string
}

interface WarehouseContextValue {
  loading: boolean
  warehouses: WarehouseOption[]
  currentWarehouseId: string
  current: WarehouseOption | null
  setCurrentWarehouseId: (id: string) => void
  defaultWarehouseId: string | null
}

const WarehouseContext = createContext<WarehouseContextValue | null>(null)
const CURRENT_KEY = 'openwcs.warehouse'

interface MasterDataWarehouse {
  id: string
  code: string
  name: string
}
interface MyAccess {
  warehouses: string[]
  defaultWarehouse: string | null
}

export function WarehouseProvider({ children }: { children: ReactNode }) {
  const { session, roles } = useAuth()
  const isAdmin = roles.includes('ADMIN')

  const [loading, setLoading] = useState(true)
  const [warehouses, setWarehouses] = useState<WarehouseOption[]>([])
  const [defaultWarehouseId, setDefaultWarehouseId] = useState<string | null>(null)
  const [currentWarehouseId, setCurrent] = useState('')

  useEffect(() => {
    if (!session) return
    let cancelled = false
    setLoading(true)
    Promise.all([
      fetch('/api/master-data/warehouses?size=200')
        .then((r) => (r.ok ? r.json() : { content: [] }))
        .catch(() => ({ content: [] })),
      fetch('/api/iam/warehouse-access/me')
        .then((r) => (r.ok ? r.json() : { warehouses: [], defaultWarehouse: null }))
        .catch(() => ({ warehouses: [], defaultWarehouse: null })),
    ]).then(([page, access]: [{ content?: MasterDataWarehouse[] }, MyAccess]) => {
      if (cancelled) return
      const all: WarehouseOption[] = (page.content ?? []).map((w) => ({ id: w.id, code: w.code, name: w.name }))
      const allowedIds = new Set(access.warehouses ?? [])
      // Admins aren't warehouse-scoped — they may pick any warehouse.
      const list = isAdmin ? all : all.filter((w) => allowedIds.has(w.id))
      setWarehouses(list)
      const def = access.defaultWarehouse && list.some((w) => w.id === access.defaultWarehouse)
        ? access.defaultWarehouse
        : null
      setDefaultWarehouseId(def)
      // Restore a still-allowed prior selection; else the default; else the first allowed.
      const persisted = sessionStorage.getItem(CURRENT_KEY)
      const pick =
        (persisted && list.some((w) => w.id === persisted) && persisted) ||
        def ||
        (list[0]?.id ?? '')
      setCurrent(pick)
    }).finally(() => {
      if (!cancelled) setLoading(false)
    })
    return () => {
      cancelled = true
    }
  }, [session, isAdmin])

  const value = useMemo<WarehouseContextValue>(() => ({
    loading,
    warehouses,
    currentWarehouseId,
    current: warehouses.find((w) => w.id === currentWarehouseId) ?? null,
    defaultWarehouseId,
    setCurrentWarehouseId: (id: string) => {
      if (warehouses.some((w) => w.id === id)) {
        setCurrent(id)
        sessionStorage.setItem(CURRENT_KEY, id)
      }
    },
  }), [loading, warehouses, currentWarehouseId, defaultWarehouseId])

  return <WarehouseContext.Provider value={value}>{children}</WarehouseContext.Provider>
}

export function useWarehouse(): WarehouseContextValue {
  const ctx = useContext(WarehouseContext)
  if (!ctx) throw new Error('useWarehouse must be used within WarehouseProvider')
  return ctx
}
