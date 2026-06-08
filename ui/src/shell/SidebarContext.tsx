import { createContext, useContext, useEffect, useMemo, useState } from 'react'

// Shares the app sidebar's collapsed state so focus screens (e.g. the GTP operator console) can
// collapse it for maximum real estate and restore the operator's prior choice on exit. The state is
// still persisted to localStorage (openwcs.sidebarCollapsed) and driven by the topbar toggle button.
interface SidebarValue {
  collapsed: boolean
  setCollapsed: (next: boolean | ((prev: boolean) => boolean)) => void
}

const SidebarContext = createContext<SidebarValue | null>(null)

export function SidebarProvider({ children }: { children: React.ReactNode }) {
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem('openwcs.sidebarCollapsed') === '1')
  useEffect(() => {
    localStorage.setItem('openwcs.sidebarCollapsed', collapsed ? '1' : '0')
  }, [collapsed])

  const value = useMemo<SidebarValue>(() => ({ collapsed, setCollapsed }), [collapsed])
  return <SidebarContext.Provider value={value}>{children}</SidebarContext.Provider>
}

export function useSidebar(): SidebarValue {
  const ctx = useContext(SidebarContext)
  if (!ctx) throw new Error('useSidebar must be used within a SidebarProvider')
  return ctx
}
