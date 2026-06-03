import { useState } from 'react'
import TopologyEditor from './topology/TopologyEditor'
import ProcessDesigner from './process/ProcessDesigner'
import SlottingScreen from './slotting/SlottingScreen'

// Admin screens (build.md §8, §11; ADR 0003): the conveyor topology editor, the BPMN process
// designer, and slotting (pick-face + block). More screens get added to the nav as they're built.
type View = 'topology' | 'processes' | 'slotting'

export default function App() {
  const [view, setView] = useState<View>('topology')
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <nav style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', padding: '0.5rem', borderBottom: '1px solid #ccc' }}>
        <strong style={{ marginRight: '1rem' }}>openWCS</strong>
        <button onClick={() => setView('topology')} disabled={view === 'topology'}>Conveyor topology</button>
        <button onClick={() => setView('processes')} disabled={view === 'processes'}>Processes</button>
        <button onClick={() => setView('slotting')} disabled={view === 'slotting'}>Slotting</button>
      </nav>
      <div style={{ flex: 1, minHeight: 0 }}>
        {view === 'topology' && <TopologyEditor />}
        {view === 'processes' && <ProcessDesigner />}
        {view === 'slotting' && <SlottingScreen />}
      </div>
    </div>
  )
}
