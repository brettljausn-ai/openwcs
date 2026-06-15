// Shared chrome for an area dashboard: page head with eyebrow + last-updated/stale stamp, the
// no-warehouse guard, and the standard hero-row + chart-row layout. Keeps the five menu screens lean.
import type { ReactNode } from 'react'
import { useT } from '../i18n/useT'
import { useWarehouse } from '../warehouse/WarehouseContext'
import { ChartCard } from '../reporting/charts'
import { LastUpdated } from './components'

export function NoWarehouse() {
  const t = useT('dashboards')
  return (
    <div className="app-content">
      <div className="glass" style={{ padding: '2.5rem', textAlign: 'center', color: 'var(--text-dim)' }}>
        {t('selectWarehouse', 'Select a warehouse in the top bar to load the dashboards.')}
      </div>
    </div>
  )
}

export function PageHead({ title, intro, updatedAt, stale }: { title: string; intro: string; updatedAt: Date | null; stale?: boolean }) {
  const t = useT('dashboards')
  return (
    <div className="page-head" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', gap: '1rem' }}>
      <div>
        <span className="eyebrow">{t('eyebrow', 'Dashboards')}</span>
        <h1>{title}</h1>
        <p>{intro}</p>
      </div>
      <LastUpdated at={updatedAt} stale={stale} />
    </div>
  )
}

/** A row of heroes in a quiet card. */
export function HeroRow({ children }: { children: ReactNode }) {
  return (
    <div className="glass" style={{ padding: '1.1rem 1.2rem', marginBottom: '1rem' }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '1.4rem' }}>{children}</div>
    </div>
  )
}

export function ChartRow({ children }: { children: ReactNode }) {
  return <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '1rem' }}>{children}</div>
}

export { ChartCard }
