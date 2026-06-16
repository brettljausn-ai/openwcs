import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { SCREENS, ScreenDef } from './auth/screens'
import Login from './auth/Login'
import AppShell from './shell/AppShell'
import OperatorShell from './shell/OperatorShell'
import OperatorMenu from './shell/OperatorMenu'
import ProcessComingSoon from './shell/ProcessComingSoon'
import { useHandheldMode } from './shell/handheld'
import ComingSoon from './shell/ComingSoon'
import BackendOverlay from './shell/BackendOverlay'
import Dashboard from './Dashboard'
import { WarehouseProvider } from './warehouse/WarehouseContext'
import { LanguageProvider } from './i18n/LanguageContext'

// Existing feature screens (re-homed into the shell).
import TopologyEditor from './topology/TopologyEditor'
import ProcessDesigner from './process/ProcessDesigner'
import ProcessDesignScreen from './process-designer/designer/ProcessDesignScreen'
import ProcessInstancesScreen from './process-designer/instances/ProcessInstancesScreen'
import ProcessRuntimeScreen from './process-designer/runtime/ProcessRuntimeScreen'
import SlottingScreen from './slotting/SlottingScreen'

// Reserved screens — built by follow-up agents (each owns its own file).
import InboundScreen from './inbound/InboundScreen'
import OutboundScreen from './outbound/OutboundScreen'
import CountingScreen from './counting/CountingScreen'
import GtpOpsScreen from './gtpops/GtpOpsScreen'
import PickingScreen from './picking/PickingScreen'
import TransportScreen from './transport/TransportScreen'
import HardwareTwinScreen from './hardwaretwin/HardwareTwinScreen'
import StockTxnScreen from './stocktxn/StockTxnScreen'
import StockOverviewScreen from './inventory/StockOverviewScreen'
import HandlingUnitsScreen from './inventory/HandlingUnitsScreen'
import MasterDataScreen from './masterdata/MasterDataScreen'
import GtpConfigScreen from './gtpconfig/GtpConfigScreen'
import SettingsScreen from './settings/SettingsScreen'
import UsersScreen from './users/UsersScreen'
import AccessControlScreen from './access/AccessControlScreen'
import WarehouseAccessScreen from './warehouseaccess/WarehouseAccessScreen'
import MaterialFlowReport from './reporting/MaterialFlowReport'
import AsrsReport from './reporting/AsrsReport'
import StockReport from './reporting/StockReport'
import OrderFlowReport from './reporting/OrderFlowReport'
import SystemInfoScreen from './systeminfo/SystemInfoScreen'
import LogsPage from './systeminfo/LogsPage'
import DatabaseScreen from './admindb/DatabaseScreen'
import DashboardsOverview from './dashboards/Overview'
import DashboardsInbound from './dashboards/InboundDashboard'
import DashboardsOutbound from './dashboards/OutboundDashboard'
import DashboardsReplenishment from './dashboards/ReplenishmentDashboard'
import DashboardsStock from './dashboards/StockDashboard'
import DashboardsAbc from './dashboards/AbcDashboard'
import DashboardsAndon from './dashboards/AndonBoard'
import DashboardsAlertHealth from './dashboards/AlertHealthDashboard'

const COMPONENTS: Record<string, JSX.Element> = {
  dashboard: <Dashboard />,
  'dashboards:overview': <DashboardsOverview />,
  'dashboards:inbound': <DashboardsInbound />,
  'dashboards:outbound': <DashboardsOutbound />,
  'dashboards:replenishment': <DashboardsReplenishment />,
  'dashboards:stock': <DashboardsStock />,
  'dashboards:abc': <DashboardsAbc />,
  'dashboards:andon': <DashboardsAndon />,
  'dashboards:alert-health': <DashboardsAlertHealth />,
  topology: <TopologyEditor />,
  processes: <ProcessDesigner />,
  'process:design': <ProcessDesignScreen />,
  'process:instances': <ProcessInstancesScreen />,
  slotting: <SlottingScreen />,
  inbound: <InboundScreen />,
  outbound: <OutboundScreen />,
  counting: <CountingScreen />,
  // Handheld "Stock Check" process maps to the live stock-counting capture flow.
  'stock-check': <CountingScreen />,
  'gtp-ops': <GtpOpsScreen />,
  picking: <PickingScreen />,
  transport: <TransportScreen />,
  'hardware-twin': <HardwareTwinScreen />,
  'stock-transactions': <StockTxnScreen />,
  'stock-overview': <StockOverviewScreen />,
  'handling-units': <HandlingUnitsScreen />,
  'master-data:warehouses': <MasterDataScreen />,
  'master-data:skus': <MasterDataScreen />,
  'master-data:storage-blocks': <MasterDataScreen />,
  'master-data:locations': <MasterDataScreen />,
  'master-data:equipment': <MasterDataScreen />,
  'master-data:handling-unit-types': <MasterDataScreen />,
  'master-data:label-templates': <MasterDataScreen />,
  'reporting:material-flow': <MaterialFlowReport />,
  'reporting:asrs': <AsrsReport />,
  'reporting:stock': <StockReport />,
  'reporting:inbound': <OrderFlowReport direction="INBOUND" />,
  'reporting:outbound': <OrderFlowReport direction="OUTBOUND" />,
  'gtp-config': <GtpConfigScreen />,
  settings: <SettingsScreen />,
  users: <UsersScreen />,
  'access-control': <AccessControlScreen />,
  'warehouse-access': <WarehouseAccessScreen />,
  'system-info': <SystemInfoScreen />,
  'admin-database': <DatabaseScreen />,
}

function RequireAuth({ children }: { children: JSX.Element }) {
  const { session } = useAuth()
  const location = useLocation()
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return children
}

/** Route element that also enforces screen-level access (catalog + role/user overrides). An optional
 * `element` overrides the catalog lookup — used for sub-routes (e.g. the full logs page) that share
 * a screen's access but render a different component. */
function Guarded({ screen, element }: { screen: ScreenDef; element?: JSX.Element }) {
  const { can } = useAuth()
  if (!can(screen)) {
    return (
      <ComingSoon
        title="Not authorised"
        note="You don't have access to this screen. Ask an administrator to grant it under Access control."
      />
    )
  }
  return element ?? COMPONENTS[screen.key] ?? <ComingSoon title={screen.label} />
}

const SYSTEM_INFO_SCREEN = SCREENS.find((s) => s.key === 'system-info')!

// The operator processes shown as tiles in the handheld menu (catalog order).
const PROCESS_SCREENS = SCREENS.filter((s) => s.process)

/** Renders a handheld process: coming-soon processes show the honest placeholder; the rest reuse
 *  the same access guard + component map as the desktop app. */
function GuardedProcess({ screen }: { screen: ScreenDef }) {
  const { can } = useAuth()
  if (!can(screen)) {
    return <ProcessComingSoon title={screen.label} />
  }
  if (screen.comingSoon) {
    return <ProcessComingSoon title={screen.label} />
  }
  return COMPONENTS[screen.key] ?? <ProcessComingSoon title={screen.label} />
}

/** The full desktop app (sidebar shell + every screen). Unchanged from the original App routes. */
function DesktopRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        element={
          <RequireAuth>
            <LanguageProvider>
              <WarehouseProvider>
                <AppShell />
              </WarehouseProvider>
            </LanguageProvider>
          </RequireAuth>
        }
      >
        {SCREENS.map((s) =>
          s.path === '/' ? (
            <Route key={s.key} index element={<Guarded screen={s} />} />
          ) : (
            <Route key={s.key} path={s.path} element={<Guarded screen={s} />} />
          ),
        )}
        {/* Full-page service logs (opened from the System info modal); shares its ADMIN access. */}
        <Route
          path="/system-info/logs/:name"
          element={<Guarded screen={SYSTEM_INFO_SCREEN} element={<LogsPage />} />}
        />
        {/* /master-data → first catalog (old links / nav land on Warehouses). */}
        <Route path="/master-data" element={<Navigate to="/master-data/warehouses" replace />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

/** The stripped handheld/PWA operator app: a tile menu (index) + the operator-process routes only.
 *  Any non-process URL redirects to the menu, so the desktop screens are never reachable here. */
function OperatorRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        element={
          <RequireAuth>
            <LanguageProvider>
              <WarehouseProvider>
                <OperatorShell />
              </WarehouseProvider>
            </LanguageProvider>
          </RequireAuth>
        }
      >
        {/* Post-login landing in handheld mode is the operator menu (not the dashboard). */}
        <Route index element={<OperatorMenu />} />
        {/* Static, hand-coded operator processes from the catalog (Picking, Stock Check, …). These
            literal paths (e.g. /process/goods-in) take precedence over the dynamic :key route. */}
        {PROCESS_SCREENS.map((s) => (
          <Route key={s.key} path={s.path} element={<GuardedProcess screen={s} />} />
        ))}
        {/* Client-driven runtime for any ACTIVE configurable process. */}
        <Route path="/process/:key" element={<ProcessRuntimeScreen />} />
      </Route>
      {/* A non-process URL hit in handheld mode redirects to the menu. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

function Shell() {
  const handheld = useHandheldMode()
  return handheld ? <OperatorRoutes /> : <DesktopRoutes />
}

export default function App() {
  return (
    <BrowserRouter>
      <BackendOverlay />
      <AuthProvider>
        <Shell />
      </AuthProvider>
    </BrowserRouter>
  )
}
