import { lazy, Suspense, useState } from 'react'
import { useT } from '../i18n/useT'
import RoutingGraphTables from './RoutingGraphTables'
// Lazy so three.js / r3f are code-split and only fetched when the 3D layout tab is opened.
const AutomationTopology3D = lazy(() => import('./AutomationTopology3D'))

type TopologyTab = '3d' | 'routing'

// Tabbed shell around the two topology views: the 3D physical automation layout (default) and the
// routing graph, a table-based inspector (RoutingGraphTables) over the generated node/edge graph.
export default function TopologyEditor() {
  const t = useT('topology')
  const [tab, setTab] = useState<TopologyTab>('3d')
  // Fold the page chrome (title + level meta) away to give the drawing canvas more height.
  // Persisted so the editor reopens the way the user left it.
  const [collapsed, setCollapsed] = useState<boolean>(() => {
    try {
      return localStorage.getItem('topoChromeCollapsed') === '1'
    } catch {
      return false
    }
  })
  const toggleCollapsed = () =>
    setCollapsed((c) => {
      const next = !c
      try {
        localStorage.setItem('topoChromeCollapsed', next ? '1' : '0')
      } catch {
        /* ignore */
      }
      return next
    })
  return (
    <div className="app-content">
      {!collapsed && (
        <div className="page-head">
          <span className="eyebrow">{t('eyebrow', 'Configuration')}</span>
          <h1>{t('title', 'Automation topology')}</h1>
        </div>
      )}
      <div className="topo-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === '3d'}
          className={`topo-tab${tab === '3d' ? ' is-active' : ''}`}
          onClick={() => setTab('3d')}
        >
          {t('tab3dLayout', '3D layout')}
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'routing'}
          className={`topo-tab${tab === 'routing' ? ' is-active' : ''}`}
          onClick={() => setTab('routing')}
        >
          {t('tabRoutingGraph', 'Routing graph')}
        </button>
      </div>
      {tab === '3d' ? (
        <Suspense fallback={<div className="glass card-pad">{t('loading3dEditor', 'Loading 3D editor…')}</div>}>
          <AutomationTopology3D collapsed={collapsed} onToggleChrome={toggleCollapsed} />
        </Suspense>
      ) : (
        <RoutingGraphTables />
      )}
      <style>{`
        .topo-tabs { display: flex; gap: .4rem; margin-bottom: .8rem; }
        .topo-tab {
          padding: .45rem 1rem; border-radius: 999px; cursor: pointer; font-size: .875rem;
          background: var(--glass-bg); color: var(--text); border: 1px solid var(--glass-border);
          font-family: var(--font-body); transition: all .15s;
        }
        .topo-tab:hover { border-color: var(--glass-border-bright); }
        .topo-tab.is-active {
          background: rgba(141, 198, 63, .15); color: var(--herbal-lime); border-color: var(--glass-border-bright);
        }
      `}</style>
    </div>
  )
}
