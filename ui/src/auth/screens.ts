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

export type Section = 'Master data' | 'Operations' | 'Reporting' | 'Engineering' | 'Configuration' | 'Administration'

export interface ScreenDef {
  key: string
  label: string
  path: string
  section?: Section // omitted = top-level (Dashboard)
  icon: string
  defaultRoles: Role[]
  description: string
}

export const SCREENS: ScreenDef[] = [
  { key: 'dashboard', label: 'Dashboard', path: '/', icon: '◫', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR', 'VIEWER'], description: 'Overview and quick links into every area of the warehouse control system.' },

  // Operations — day-to-day execution
  { key: 'inbound', label: 'Inbound orders', path: '/inbound', section: 'Operations', icon: '⇣', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Inbound orders & ASNs — receive goods and track put-away.' },
  { key: 'outbound', label: 'Outbound orders', path: '/outbound', section: 'Operations', icon: '⇡', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Outbound orders — release, allocate, pick and dispatch.' },
  { key: 'counting', label: 'Stock counting', path: '/counting', section: 'Operations', icon: '☑', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Cycle / stock counting — tasks, capture, variance and reconciliation.' },
  { key: 'gtp-ops', label: 'GTP workplaces', path: '/gtp', section: 'Operations', icon: '☷', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Goods-to-person operator consoles — one active session per workplace.' },
  { key: 'transport', label: 'Transport', path: '/transport', section: 'Operations', icon: '⇄', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Live device-task / transport overview across equipment.' },
  { key: 'hardware-twin', label: 'Hardware visualisation', path: '/hardware-twin', section: 'Operations', icon: '◳', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Live 3D view of the automation hardware — equipment activity and handling units moving through the system.' },
  { key: 'stock-transactions', label: 'Stock transactions', path: '/stock-transactions', section: 'Operations', icon: '≡', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Event-sourced stock movement / transaction log.' },
  { key: 'stock-overview', label: 'Stock overview', path: '/stock-overview', section: 'Operations', icon: '▥', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'What is currently in stock, by handling unit — quantities and availability.' },
  { key: 'handling-units', label: 'Handling units', path: '/handling-units', section: 'Operations', icon: '▢', defaultRoles: ['ADMIN', 'SUPERVISOR', 'OPERATOR'], description: 'Registry of physical handling units (cartons, pallets, totes) — code, type, location and status.' },

  // Reporting: analytics over the accumulated operational history (read-only)
  { key: 'reporting:material-flow', label: 'Material flow', path: '/reporting/material-flow', section: 'Reporting', icon: '∿', defaultRoles: ['ADMIN', 'SUPERVISOR', 'VIEWER'], description: 'Scan quality per scan point and day, scanners needing attention, and a conveyor traffic heatmap.' },
  { key: 'reporting:asrs', label: 'ASRS', path: '/reporting/asrs', section: 'Reporting', icon: '▦', defaultRoles: ['ADMIN', 'SUPERVISOR', 'VIEWER'], description: 'Storage density history and forecast, a storage-movement heatmap, and movements per device.' },
  { key: 'reporting:stock', label: 'Stock', path: '/reporting/stock', section: 'Reporting', icon: '▤', defaultRoles: ['ADMIN', 'SUPERVISOR', 'VIEWER'], description: 'Stock per SKU in single quantities, split between available, allocated and unavailable.' },
  { key: 'reporting:inbound', label: 'Inbound', path: '/reporting/inbound', section: 'Reporting', icon: '⇣', defaultRoles: ['ADMIN', 'SUPERVISOR', 'VIEWER'], description: 'Expected and active inbound, daily volumes over the last 90 days and hour-of-day peaks.' },
  { key: 'reporting:outbound', label: 'Outbound', path: '/reporting/outbound', section: 'Reporting', icon: '⇡', defaultRoles: ['ADMIN', 'SUPERVISOR', 'VIEWER'], description: 'Expected and active outbound, daily volumes over the last 90 days and hour-of-day peaks.' },

  // Engineering — design & modelling
  { key: 'topology', label: 'Automation topology', path: '/topology', section: 'Engineering', icon: '⊹', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Place, size and connect automation equipment (conveyors, ASRS, sorters) on warehouse levels.' },
  { key: 'processes', label: 'Processes', path: '/processes', section: 'Engineering', icon: '⇉', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Design and deploy BPMN processes; run instances and tasks.' },
  { key: 'slotting', label: 'Slotting', path: '/slotting', section: 'Engineering', icon: '▦', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Pick-face and automated-block slotting & replenishment policy.' },
  { key: 'master-data:equipment', label: 'Equipment', path: '/master-data/equipment', section: 'Engineering', icon: '⚙', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Devices (conveyors, ASRS, sorters) and the adapter endpoints the WCS drives.' },

  // Master data — its own section; each catalog is an individually-routed, access-controllable screen.
  { key: 'master-data:warehouses', label: 'Warehouses', path: '/master-data/warehouses', section: 'Master data', icon: '⌂', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Warehouses / sites — the top-level locations everything is scoped to.' },
  { key: 'master-data:skus', label: 'SKUs', path: '/master-data/skus', section: 'Master data', icon: '▤', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'SKU catalog with units of measure and barcodes (host-owned, read-only).' },
  { key: 'master-data:storage-blocks', label: 'Storage blocks', path: '/master-data/storage-blocks', section: 'Master data', icon: '▦', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Storage pools / zones for slotting (manual pick faces and automated systems).' },
  { key: 'master-data:locations', label: 'Locations', path: '/master-data/locations', section: 'Master data', icon: '⊞', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Addressable storage locations and their rack geometry.' },
  { key: 'master-data:handling-unit-types', label: 'Handling unit types', path: '/master-data/handling-unit-types', section: 'Master data', icon: '⬡', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Container types (totes, pallets, cartons) and their capabilities.' },
  { key: 'master-data:label-templates', label: 'Label templates', path: '/master-data/label-templates', section: 'Master data', icon: '⎙', defaultRoles: ['ADMIN', 'SUPERVISOR'], description: 'Dispatch / handling-unit label templates.' },

  // Configuration — system config
  { key: 'gtp-config', label: 'GTP workplaces', path: '/gtp-config', section: 'Configuration', icon: '⚙', defaultRoles: ['ADMIN'], description: 'Configure GTP workplaces, nodes and operating modes.' },
  { key: 'settings', label: 'Settings', path: '/settings', section: 'Configuration', icon: '⚙', defaultRoles: ['ADMIN'], description: 'System settings, policies and integration endpoints.' },

  // Administration — users & access
  { key: 'users', label: 'User management', path: '/users', section: 'Administration', icon: '☻', defaultRoles: ['ADMIN'], description: 'Manage Keycloak users, roles and credentials.' },
  { key: 'access-control', label: 'Access control', path: '/access-control', section: 'Administration', icon: '⚿', defaultRoles: ['ADMIN'], description: 'Map screens to roles and users.' },
  { key: 'warehouse-access', label: 'Warehouse access', path: '/warehouse-access', section: 'Administration', icon: '⌂', defaultRoles: ['ADMIN'], description: 'Map users to the warehouses they may work in and set each user\'s default.' },
  { key: 'system-info', label: 'System info', path: '/system-info', section: 'Administration', icon: 'ⓘ', defaultRoles: ['ADMIN'], description: 'Version, health and logs for every service and adapter.' },
  { key: 'admin-database', label: 'Database', path: '/admin/database', section: 'Administration', icon: '⛁', defaultRoles: ['ADMIN'], description: 'Browse schemas and tables and run read-only SELECT queries against the shared database.' },
]

export const SECTION_ORDER: Section[] = ['Master data', 'Operations', 'Reporting', 'Engineering', 'Configuration', 'Administration']

// Access has three levels: OFF (no access — represented as the absence of an entry / null),
// READ (view-only) and WRITE (full). A screen with a WRITE level lets the user perform writes;
// READ shows the screen but write controls are hidden/disabled (enforced per screen and, for the
// write-heavy screens, at the gateway too).
export type AccessLevel = 'read' | 'write'

/**
 * Per-screen access override from the Access Control backend. Each role / username maps to its
 * level; an absent role/user is OFF for that overridden screen. (The wire form is lowercase
 * {@code 'read'}/{@code 'write'}.) An override only "takes over" from the defaults when it has at
 * least one role or user entry.
 */
export type AccessOverrides = Record<string, { roles?: Record<string, AccessLevel>; users?: Record<string, AccessLevel> }>

/** WRITE beats READ beats OFF. */
function stronger(a: AccessLevel | null, b: AccessLevel | null): AccessLevel | null {
  if (a === 'write' || b === 'write') return 'write'
  if (a === 'read' || b === 'read') return 'read'
  return null
}

/**
 * A screen's built-in default level for a role: OFF if the role isn't in {@code defaultRoles};
 * otherwise READ for VIEWER and for the read-only Reporting section, WRITE for everyone else.
 */
export function defaultLevel(screen: ScreenDef, role: string): AccessLevel | null {
  if (!screen.defaultRoles.includes(role as Role)) return null
  if (role === 'VIEWER' || screen.section === 'Reporting') return 'read'
  return 'write'
}

type AccessCtx = { roles: string[]; username: string; overrides?: AccessOverrides }

/** The user's effective access level on a screen: 'write' | 'read' | null (= no access). */
export function accessLevel(screen: ScreenDef, ctx: AccessCtx): AccessLevel | null {
  if (ctx.roles.includes('ADMIN')) return 'write' // admin bypass — never lock out admin, always full
  const override = ctx.overrides?.[screen.key]
  const overridden = override && ((override.roles && Object.keys(override.roles).length) || (override.users && Object.keys(override.users).length))
  let level: AccessLevel | null = null
  if (overridden) {
    if (override?.users) level = stronger(level, override.users[ctx.username] ?? null)
    if (override?.roles) for (const r of ctx.roles) level = stronger(level, override.roles[r] ?? null)
  } else {
    for (const r of ctx.roles) level = stronger(level, defaultLevel(screen, r))
  }
  return level
}

export function canAccess(screen: ScreenDef, ctx: AccessCtx): boolean {
  return accessLevel(screen, ctx) !== null
}

export function canWrite(screen: ScreenDef, ctx: AccessCtx): boolean {
  return accessLevel(screen, ctx) === 'write'
}

export function accessibleScreens(ctx: AccessCtx): ScreenDef[] {
  return SCREENS.filter((s) => canAccess(s, ctx))
}
