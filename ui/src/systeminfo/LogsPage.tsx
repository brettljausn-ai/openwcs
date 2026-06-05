import { Link, useParams } from 'react-router-dom'
import LogViewer from './LogViewer'

// Full-page logs for one service (route /system-info/logs/:name), opened from the System info
// table's log modal. Same viewer as the modal but full-height, so the filter + scrollback have room.
export default function LogsPage() {
  const { name = '' } = useParams()
  return (
    <div className="app-content" style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 5rem)' }}>
      <div className="page-head">
        <div className="eyebrow">
          openWCS · Administration · <Link to="/system-info">System info</Link>
        </div>
        <h1 style={{ fontFamily: 'var(--font-mono)' }}>{name} · logs</h1>
      </div>
      <section className="glass card-pad" style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <LogViewer name={name} fillHeight />
      </section>
    </div>
  )
}
