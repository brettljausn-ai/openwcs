import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { SCREENS, ScreenDef } from './auth/screens'
import Login from './auth/Login'
import AppShell from './shell/AppShell'
import ComingSoon from './shell/ComingSoon'
import Dashboard from './Dashboard'
import { WarehouseProvider } from './warehouse/WarehouseContext'

// Existing feature screens (re-homed into the shell).
import TopologyEditor from './topology/TopologyEditor'
import ProcessDesigner from './process/ProcessDesigner'
import SlottingScreen from './slotting/SlottingScreen'

// Reserved screens — built by follow-up agents (each owns its own file).
import InboundScreen from './inbound/InboundScreen'
import OutboundScreen from './outbound/OutboundScreen'
import CountingScreen from './counting/CountingScreen'
import GtpOpsScreen from './gtpops/GtpOpsScreen'
import TransportScreen from './transport/TransportScreen'
import StockTxnScreen from './stocktxn/StockTxnScreen'
import StockOverviewScreen from './inventory/StockOverviewScreen'
import HandlingUnitsScreen from './inventory/HandlingUnitsScreen'
import MasterDataScreen from './masterdata/MasterDataScreen'
import GtpConfigScreen from './gtpconfig/GtpConfigScreen'
import SettingsScreen from './settings/SettingsScreen'
import UsersScreen from './users/UsersScreen'
import AccessControlScreen from './access/AccessControlScreen'
import WarehouseAccessScreen from './warehouseaccess/WarehouseAccessScreen'
import SystemInfoScreen from './systeminfo/SystemInfoScreen'
import LogsPage from './systeminfo/LogsPage'

const COMPONENTS: Record<string, JSX.Element> = {
  dashboard: <Dashboard />,
  topology: <TopologyEditor />,
  processes: <ProcessDesigner />,
  slotting: <SlottingScreen />,
  inbound: <InboundScreen />,
  outbound: <OutboundScreen />,
  counting: <CountingScreen />,
  'gtp-ops': <GtpOpsScreen />,
  transport: <TransportScreen />,
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
  'gtp-config': <GtpConfigScreen />,
  settings: <SettingsScreen />,
  users: <UsersScreen />,
  'access-control': <AccessControlScreen />,
  'warehouse-access': <WarehouseAccessScreen />,
  'system-info': <SystemInfoScreen />,
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

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            element={
              <RequireAuth>
                <WarehouseProvider>
                  <AppShell />
                </WarehouseProvider>
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
      </AuthProvider>
    </BrowserRouter>
  )
}
