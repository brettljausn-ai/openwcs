// Canonical screen / permission catalog. EVERY screen in the app is listed here with a
// stable permission `key`, its route, the sidebar section, and the roles that may access it
// by default. This is the source of truth for nav rendering and route guarding.
//
// Access can be overridden per screen by role and/or user via the Access Control screen
// (iam: GET/PUT /api/iam/screen-access). When an override exists for a screen it replaces
// the defaults; otherwise `defaultRoles` apply. ADMIN always has access (so an admin can
// never lock themselves out of Access Control).
//
// Agents adding a screen MUST add it here.

export type Role = 'ADMIN' | 'SUPERVISOR' | 'OPERATOR' | 'VIEWER'

export type Section = 'Operations' | 'Engineering' | 'Configuration' | 'Administration'

export interface ScreenDef {
  key: string
  label: string
  path: string
  section?: Section // omitted = top-level (Dashboard)
  icon: string
  defaultRoles: Role[]
  description: string
  children?: { label: string; path: string }[] // optional nested sub-pages (a second menu level)
}

export const SCREENS: ScreenDef[] = [
  { key: 'dashboard', label: 'Dashboard', path: '/', icon: '◫', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER'], description: 'Overview and quick links into every area of the warehouse control system.' },

  // Operations — day-to-day execution
  { key: 'inbound', label: 'Inbound orders', path: '/inbound', section: 'Operations', icon: '⇣', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Inbound orders & ASNs — receive goods and track put-away.' },
  { key: 'outbound', label: 'Outbound orders', path: '/outbound', section: 'Operations', icon: '⇡', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Outbound orders — release, allocate, pick and dispatch.' },
  { key: 'counting', label: 'Stock counting', path: '/counting', section: 'Operations', icon: '☑', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Cycle / stock counting — tasks, capture, variance and reconciliation.' },
  { key: 'gtp-ops', label: 'GTP workplaces', path: '/gtp', section: 'Operations', icon: '☷', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Goods-to-person operator consoles — one active session per workplace.' },
  { key: 'transport', label: 'Transport', path: '/transport', section: 'Operations', icon: '⇄', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Live device-task / transport overview across equipment.' },
  { key: 'stock-transactions', label: 'Stock transactions', path: '/stock-transactions', section: 'Operations', icon: '≡', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Event-sourced stock movement / transaction log.' },

  // Engineering — design & modelling
  { key: 'topology', label: 'Conveyor topology', path: '/topology', section: 'Engineering', icon: '⊹', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Model the conveyor network, controllers (PLCs) and loops.' },
  { key: 'processes', label: 'Processes', path: '/processes', section: 'Engineering', icon: '⇉', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Design and deploy BPMN processes; run instances and tasks.' },
  { key: 'slotting', label: 'Slotting', path: '/slotting', section: 'Engineering', icon: '▦', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Pick-face and automated-block slotting & replenishment policy.' },

  // Configuration — master & system config
  { key: 'master-data', label: 'Master data', path: '/master-data', section: 'Configuration', icon: '⛁', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Warehouses, SKUs, storage blocks, locations, equipment, label templates.', children: [
    { label: 'Warehouses', path: '/master-data/warehouses' },
    { label: 'SKUs', path: '/master-data/skus' },
    { label: 'Storage blocks', path: '/master-data/storage-blocks' },
    { label: 'Locations', path: '/master-data/locations' },
    { label: 'Equipment', path: '/master-data/equipment' },
    { label: 'Label templates', path: '/master-data/label-templates' },
  ] },
  { key: 'gtp-config', label: 'GTP workplaces', path: '/gtp-config', section: 'Configuration', icon: '⚙', defaultRoles: ['ADMIN'], description: 'Configure GTP workplaces, nodes and operating modes.' },
  { key: 'settings', label: 'Settings', path: '/settings', section: 'Configuration', icon: '⚙', defaultRoles: ['ADMIN'], description: 'System settings, policies and integration endpoints.' },

  // Administration — users & access
  { key: 'users', label: 'User management', path: '/users', section: 'Administration', icon: '☻', defaultRoles: ['ADMIN'], description: 'Manage Keycloak users, roles and credentials.' },
  { key: 'access-control', label: 'Access control', path: '/access-control', section: 'Administration', icon: '⚿', defaultRoles: ['ADMIN'], description: 'Map screens to roles and users.' },
  { key: 'warehouse-access', label: 'Warehouse access', path: '/warehouse-access', section: 'Administration', icon: '⌂', defaultRoles: ['ADMIN'], description: 'Map users to the warehouses they may work in and set each user\'s default.' },
]

export const SECTION_ORDER: Section[] = ['Operations', 'Engineering', 'Configuration', 'Administration']

/** Per-screen access override returned by the Access Control backend. */
export type AccessOverrides = Record<string, { roles?: string[]; users?: string[] }>

export function canAccess(
  screen: ScreenDef,
  ctx: { roles: string[]; username: string; overrides?: AccessOverrides },
): boolean {
  if (ctx.roles.includes('ADMIN')) return true // admin bypass — never lock out admin
  const override = ctx.overrides?.[screen.key]
  if (override && (override.roles?.length || override.users?.length)) {
    if (override.users?.includes(ctx.username)) return true
    return !!override.roles?.some((r) => ctx.roles.includes(r))
  }
  return screen.defaultRoles.some((r) => ctx.roles.includes(r))
}

export function accessibleScreens(ctx: { roles: string[]; username: string; overrides?: AccessOverrides }): ScreenDef[] {
  return SCREENS.filter((s) => canAccess(s, ctx))
}
