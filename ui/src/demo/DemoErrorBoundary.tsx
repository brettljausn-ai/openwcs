// Per-screen error boundary for the public live demo (VITE_DEMO builds only). The demo serves a
// curated fixture snapshot, so a screen could still hit an endpoint we have not mocked, render a
// shape it does not expect, and throw during render. Without a boundary that error bubbles to the
// React root and the WHOLE app goes blank. This catches it at the routed-screen level and shows a
// styled card (reusing the app's .glass / .btn classes) with a link back to the dashboard, so one
// unsupported screen never takes down the rest of the demo.
//
// Demo-only: App.tsx wraps screens with this only when IS_DEMO, so the production app is unchanged.

import { Component, ErrorInfo, ReactNode } from 'react'
import { Link } from 'react-router-dom'

interface Props {
  children: ReactNode
  // Re-mount the boundary when the route changes so navigating away from a broken screen recovers.
  resetKey?: string
}

interface State {
  hasError: boolean
}

export default class DemoErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Keep a console trace so a coverage gap is still diagnosable, but never surface it to the user.
    console.error('[demo] screen render error (showing fallback):', error, info)
  }

  componentDidUpdate(prev: Props): void {
    // A new route key means a fresh screen: clear the error so the next screen gets a clean render.
    if (this.state.hasError && prev.resetKey !== this.props.resetKey) {
      this.setState({ hasError: false })
    }
  }

  render(): ReactNode {
    if (!this.state.hasError) return this.props.children
    return (
      <div className="app-content">
        <section
          className="glass card-pad"
          style={{ maxWidth: 560, margin: '3rem auto', textAlign: 'center' }}
        >
          <div style={{ fontSize: '2rem', marginBottom: '.5rem' }} aria-hidden="true">
            ◑
          </div>
          <h2 style={{ marginTop: 0 }}>This screen is not available in the demo</h2>
          <p className="muted">
            The public demo runs on a fixed sample dataset, so some screens are not wired up here. The
            rest of the demo is unaffected.
          </p>
          <Link className="btn btn-primary" to="/">
            Back to the dashboard
          </Link>
        </section>
      </div>
    )
  }
}
