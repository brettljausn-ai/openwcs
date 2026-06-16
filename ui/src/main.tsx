import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './theme/tokens.css'
import './theme/app.css'
import { registerSW } from 'virtual:pwa-register'

// Register the service worker for the installable RF-picking PWA. autoUpdate: when a new build is
// deployed, the SW activates it silently and we reload the page so handhelds always run the latest
// shell without an operator having to act. The SW precaches the app shell so a Wi-Fi blip on a
// rugged scanner doesn't white-screen the device. Registration is a no-op in dev (devOptions off).
registerSW({
  immediate: true,
  onNeedRefresh() {
    // Silent auto-update: take the new version on the next tick. The offline confirm queue is
    // durable (IndexedDB/localStorage), so reloading mid-shift never loses queued picks.
    window.location.reload()
  },
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
