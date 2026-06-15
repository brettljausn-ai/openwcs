// The dashboards design system. Grey/muted base, the --warning/--danger tokens as the SINGLE alert
// family, and colour is ALWAYS paired with an icon/shape so state never rides on hue alone. No radial
// gauges, no tables. The core unit is the Hero (big rounded number + bullet/limit bar vs a target with
// shaded ok/warn/critical ranges + a sparkline + a computed state colour). Multi-element panels use
// WorstFirstStrip (normalised ranked bars, colour only on threshold breaches). Recharts pieces are
// reused from ../reporting/charts wherever possible.
import type { CSSProperties, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Sparkline } from '../reporting/charts'
import { useT } from '../i18n/useT'

// ---------------------------------------------------------------- state model + tokens

export type DashState = 'ok' | 'warning' | 'critical'

const STATE_COLOR: Record<DashState, string> = {
  ok: 'var(--text-dim)', // calm/grey when healthy — colour is reserved for problems
  warning: 'var(--warning)',
  critical: 'var(--danger)',
}
// Shape pairs with colour so the state is legible without hue (≈8% of males are colour-blind).
const STATE_ICON: Record<DashState, string> = { ok: '●', warning: '▲', critical: '■' }

export function stateColor(s: DashState): string {
  return STATE_COLOR[s]
}
export function stateIcon(s: DashState): string {
  return STATE_ICON[s]
}

/** Rank states so a list of part-states can be reduced to its worst. */
export function worstState(states: DashState[]): DashState {
  if (states.includes('critical')) return 'critical'
  if (states.includes('warning')) return 'warning'
  return 'ok'
}

/** Rounded hero value: 12.4k not 12,407; one decimal under 10; whole numbers below 1000. */
export function roundedValue(n: number): string {
  if (!Number.isFinite(n)) return '-'
  const abs = Math.abs(n)
  if (abs >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (abs >= 10_000) return `${(n / 1000).toFixed(0)}k`
  if (abs >= 1000) return `${(n / 1000).toFixed(1)}k`
  if (abs > 0 && abs < 10 && !Number.isInteger(n)) return n.toFixed(1)
  return String(Math.round(n))
}

// ---------------------------------------------------------------- last-updated + stale states

export function LastUpdated({ at, stale }: { at: Date | null; stale?: boolean }) {
  const t = useT('dashboards')
  if (stale) {
    return (
      <span style={{ fontSize: '.7rem', color: 'var(--warning)', fontFamily: 'var(--font-mono)' }}>
        {stateIcon('warning')} {t('stale', 'Stale')}
      </span>
    )
  }
  return (
    <span className="muted" style={{ fontSize: '.7rem', fontFamily: 'var(--font-mono)' }}>
      {at ? `${t('updated', 'Updated')} ${at.toLocaleTimeString()}` : t('loading', 'Loading…')}
    </span>
  )
}

// ---------------------------------------------------------------- hero (core unit)

export interface HeroRange {
  /** Fraction 0..1 of the bar where the OK range ends and warning begins. */
  warnAt: number
  /** Fraction 0..1 where warning ends and critical begins. */
  critAt: number
}

export interface HeroProps {
  label: string
  value: number | string
  /** Shown small after the value, e.g. "%", "min", "/ 120 target". */
  unit?: ReactNode
  state: DashState
  /** Bullet bar: current value as a fraction 0..1 of the bar; omit to hide the bar. */
  fill?: number
  /** Where the shaded ok/warn/critical ranges sit on the bar (fractions 0..1). */
  ranges?: HeroRange
  /** A target marker on the bar (fraction 0..1). */
  target?: number
  /** Recent-history sparkline values. */
  spark?: number[]
  /** One-line comparison / projection beneath the value (the "vs target" context). */
  context?: ReactNode
  big?: boolean
}

/**
 * The hero. A big rounded number, a horizontal bullet/limit bar (value vs target with shaded
 * ok/warn/critical ranges), a sparkline of recent history, and a computed-state colour+icon.
 */
export function Hero({ label, value, unit, state, fill, ranges, target, spark, context, big }: HeroProps) {
  const color = stateColor(state)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '.4rem', minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '.4rem' }}>
        <span aria-hidden="true" style={{ color, fontSize: '.7rem' }}>
          {stateIcon(state)}
        </span>
        <span
          className="muted"
          style={{ fontFamily: 'var(--font-mono)', fontSize: '.62rem', letterSpacing: '.1em', textTransform: 'uppercase' }}
        >
          {label}
        </span>
      </div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: '.35rem' }}>
        <span style={{ fontSize: big ? '2.6rem' : '2rem', fontWeight: 700, lineHeight: 1, color: state === 'ok' ? 'var(--text)' : color }}>
          {typeof value === 'number' ? roundedValue(value) : value}
        </span>
        {unit && <span className="muted" style={{ fontSize: '.85rem' }}>{unit}</span>}
      </div>
      {fill != null && <BulletBar fill={fill} ranges={ranges} target={target} color={color} />}
      {context && <div className="muted" style={{ fontSize: '.72rem' }}>{context}</div>}
      {spark && spark.length >= 2 && (
        <Sparkline values={spark} width={big ? 160 : 120} height={26} color={state === 'ok' ? '#7d8a82' : color} />
      )}
    </div>
  )
}

/** Horizontal bullet/limit bar: shaded ok/warn/critical ranges, a value fill and a target tick. */
function BulletBar({ fill, ranges, target, color }: { fill: number; ranges?: HeroRange; target?: number; color: string }) {
  const clamp = (v: number) => Math.max(0, Math.min(1, v))
  const f = clamp(fill)
  const r = ranges ?? { warnAt: 0.7, critAt: 0.9 }
  const bg: CSSProperties = {
    position: 'relative',
    height: 12,
    borderRadius: 6,
    overflow: 'hidden',
    // Shaded ranges: a quiet base with low-saturation warn/crit zones (still grey-leaning).
    background: `linear-gradient(to right,
      rgba(255,255,255,.07) 0%, rgba(255,255,255,.07) ${r.warnAt * 100}%,
      rgba(244,184,96,.18) ${r.warnAt * 100}%, rgba(244,184,96,.18) ${r.critAt * 100}%,
      rgba(255,107,94,.20) ${r.critAt * 100}%, rgba(255,107,94,.20) 100%)`,
    border: '1px solid rgba(255,255,255,.08)',
  }
  return (
    <div style={bg} aria-hidden="true">
      <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: `${f * 100}%`, background: color, opacity: 0.85 }} />
      {target != null && (
        <div
          style={{ position: 'absolute', left: `${clamp(target) * 100}%`, top: -2, bottom: -2, width: 2, background: 'var(--text)' }}
        />
      )}
    </div>
  )
}

// ---------------------------------------------------------------- worst-first ranked strip

export interface StripEntry {
  label: string
  value: number
  /** When true the entry is breaching a threshold and gets the alert colour + icon. */
  breach?: boolean
  state?: DashState
  /** Optional value formatter (e.g. "12 min", "85%"). */
  display?: string
}

/**
 * Normalised ranked bars sorted worst-first. Every bar is grey unless it breaches its threshold, so
 * "all normal" reads as a flat quiet block and any deviation pops without reading the numbers.
 */
export function WorstFirstStrip({ entries, max }: { entries: StripEntry[]; max?: number }) {
  const t = useT('dashboards')
  if (entries.length === 0) {
    return <p className="muted" style={{ fontSize: '.8rem', margin: '.4rem 0' }}>{t('noEntries', 'Nothing to show — all clear.')}</p>
  }
  const hi = max ?? Math.max(...entries.map((e) => Math.abs(e.value)), 1)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '.4rem' }}>
      {entries.map((e, i) => {
        const st: DashState = e.state ?? (e.breach ? 'critical' : 'ok')
        const color = e.breach || e.state ? stateColor(st) : 'rgba(255,255,255,.18)'
        const w = Math.max(2, (Math.abs(e.value) / hi) * 100)
        return (
          <div key={`${e.label}-${i}`} style={{ display: 'flex', alignItems: 'center', gap: '.5rem' }}>
            <span style={{ width: 16, textAlign: 'center', color: e.breach ? stateColor(st) : 'transparent', fontSize: '.7rem' }} aria-hidden="true">
              {stateIcon(st)}
            </span>
            <span style={{ width: 120, fontSize: '.75rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {e.label}
            </span>
            <div style={{ flex: 1, height: 10, borderRadius: 5, background: 'rgba(255,255,255,.05)', overflow: 'hidden' }}>
              <div style={{ width: `${w}%`, height: '100%', background: color }} />
            </div>
            <span style={{ width: 64, textAlign: 'right', fontFamily: 'var(--font-mono)', fontSize: '.72rem', color: e.breach ? stateColor(st) : 'var(--text-dim)' }}>
              {e.display ?? e.value}
            </span>
          </div>
        )
      })}
    </div>
  )
}

// ---------------------------------------------------------------- alerts

export function severityState(sev: string): DashState {
  const s = sev.toUpperCase()
  if (s === 'CRITICAL' || s === 'HIGH') return 'critical'
  if (s === 'WARNING' || s === 'WARN' || s === 'MEDIUM') return 'warning'
  return 'ok'
}

/** Compact alert count by severity, shape + colour coded. Zero alerts read as a quiet "all clear". */
export function AlertBadge({ counts }: { counts: { critical: number; warning: number; info: number } }) {
  const t = useT('dashboards')
  const total = counts.critical + counts.warning + counts.info
  if (total === 0) {
    return (
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: '.35rem', fontSize: '.8rem', color: 'var(--text-dim)' }}>
        <span aria-hidden="true">{stateIcon('ok')}</span> {t('allClear', 'All clear')}
      </span>
    )
  }
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: '.6rem', fontSize: '.85rem' }}>
      {counts.critical > 0 && (
        <span style={{ color: stateColor('critical') }} title={t('critical', 'Critical')}>
          <span aria-hidden="true">{stateIcon('critical')}</span> {counts.critical}
        </span>
      )}
      {counts.warning > 0 && (
        <span style={{ color: stateColor('warning') }} title={t('warning', 'Warning')}>
          <span aria-hidden="true">{stateIcon('warning')}</span> {counts.warning}
        </span>
      )}
      {counts.info > 0 && (
        <span className="muted" title={t('info', 'Info')}>
          ● {counts.info}
        </span>
      )}
    </span>
  )
}

export interface AlertItem {
  id: string
  area: string
  severity: string
  metric: string
  value: number
  threshold: number
  sinceIso: string
}

/** Active alerts as worst-first rows (no table): icon+colour, area/metric, value vs threshold, age. */
export function AlertList({ alerts }: { alerts: AlertItem[] }) {
  const t = useT('dashboards')
  if (alerts.length === 0) {
    return (
      <p className="muted" style={{ fontSize: '.85rem', margin: '.4rem 0' }}>
        {stateIcon('ok')} {t('noActiveAlerts', 'No active alerts. A healthy floor looks quiet.')}
      </p>
    )
  }
  const rank = (a: AlertItem) => (severityState(a.severity) === 'critical' ? 0 : severityState(a.severity) === 'warning' ? 1 : 2)
  const sorted = [...alerts].sort((a, b) => rank(a) - rank(b))
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '.3rem' }}>
      {sorted.map((a) => {
        const st = severityState(a.severity)
        return (
          <div
            key={a.id}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '.6rem',
              padding: '.4rem .6rem',
              borderRadius: 8,
              border: '1px solid var(--glass-border)',
              borderLeft: `3px solid ${stateColor(st)}`,
              background: 'rgba(255,255,255,.02)',
            }}
          >
            <span aria-hidden="true" style={{ color: stateColor(st) }}>{stateIcon(st)}</span>
            <span style={{ fontSize: '.8rem', minWidth: 80, color: 'var(--text-dim)', textTransform: 'capitalize' }}>{a.area.toLowerCase()}</span>
            <span style={{ flex: 1, fontSize: '.82rem' }}>{a.metric}</span>
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: '.75rem', color: stateColor(st) }}>
              {a.value} / {a.threshold}
            </span>
            <span className="muted" style={{ fontSize: '.7rem' }}>{ageLabel(a.sinceIso, t)}</span>
          </div>
        )
      })}
    </div>
  )
}

function ageLabel(iso: string, t: (k: string, e: string) => string): string {
  const ms = Date.now() - new Date(iso).getTime()
  if (!Number.isFinite(ms) || ms < 0) return ''
  const min = Math.floor(ms / 60000)
  if (min < 60) return `${min}${t('minSuffix', 'm')}`
  const h = Math.floor(min / 60)
  return `${h}${t('hourSuffix', 'h')} ${min % 60}${t('minSuffix', 'm')}`
}

// ---------------------------------------------------------------- Pareto (ABC)

export interface ParetoDatum {
  label: string
  picks: number
  cumPct: number
  cls: 'A' | 'B' | 'C'
}

const ABC_BAR: Record<'A' | 'B' | 'C', string> = {
  A: '#8DC63F', // calm data colours (this is a frequency chart, not an alert surface)
  B: '#5aa9ff',
  C: '#7d8a82',
}

/** ABC Pareto: descending picks bars coloured by class + a cumulative-% line, with cut points. */
export function ParetoChart({ data, height = 280 }: { data: ParetoDatum[]; height?: number }) {
  const t = useT('dashboards')
  if (data.length === 0) {
    return <p className="muted" style={{ fontSize: '.85rem', padding: '1.4rem', textAlign: 'center' }}>{t('noParetoData', 'No movement data yet.')}</p>
  }
  return (
    <ResponsiveContainer width="100%" height={height}>
      <ComposedChart data={data} margin={{ top: 6, right: 12, bottom: 0, left: -12 }}>
        <CartesianGrid stroke="rgba(255,255,255,.06)" vertical={false} />
        <XAxis dataKey="label" tick={false} axisLine={{ stroke: '#2a473b' }} />
        <YAxis yAxisId="l" tick={{ stroke: '#5d6f66', fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} />
        <YAxis yAxisId="r" orientation="right" domain={[0, 100]} tick={{ stroke: '#5d6f66', fontSize: 11 }} tickLine={false} axisLine={false} tickFormatter={(v) => `${v}%`} />
        <Tooltip
          contentStyle={{ background: '#0c2a1e', border: '1px solid rgba(255,255,255,.12)', borderRadius: 8, fontSize: 12 }}
          labelStyle={{ color: '#d6e4dc' }}
        />
        <Bar yAxisId="l" dataKey="picks" name={t('picks', 'Picks')} isAnimationActive={false}>
          {data.map((d, i) => (
            <Cell key={i} fill={ABC_BAR[d.cls]} />
          ))}
        </Bar>
        <Line yAxisId="r" type="monotone" dataKey="cumPct" name={t('cumulative', 'Cumulative %')} stroke="#f4b860" strokeWidth={2} dot={false} isAnimationActive={false} />
        <ReferenceLine yAxisId="r" y={80} stroke="rgba(255,255,255,.25)" strokeDasharray="4 4" />
      </ComposedChart>
    </ResponsiveContainer>
  )
}

// ---------------------------------------------------------------- shared tile shell

/** A landing-page tile: title, alert/updated header, drill-down link, and a greyed "stale" overlay. */
export function Tile({
  title,
  to,
  drillLabel,
  stale,
  updatedAt,
  header,
  children,
}: {
  title: string
  to: string
  drillLabel: string
  stale?: boolean
  updatedAt: Date | null
  header?: ReactNode
  children: ReactNode
}) {
  return (
    <div
      className="glass"
      style={{
        padding: '1rem 1.1rem',
        display: 'flex',
        flexDirection: 'column',
        gap: '.7rem',
        opacity: stale ? 0.5 : 1,
        filter: stale ? 'grayscale(0.6)' : undefined,
        transition: 'opacity .2s',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '.5rem' }}>
        <h3 style={{ margin: 0, fontSize: '.95rem' }}>{title}</h3>
        <div style={{ display: 'flex', alignItems: 'center', gap: '.6rem' }}>
          {header}
          <LastUpdated at={updatedAt} stale={stale} />
        </div>
      </div>
      <div style={{ flex: 1 }}>{children}</div>
      <Link to={to} className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }}>
        {drillLabel} →
      </Link>
    </div>
  )
}

/** Recharts simple bars by hour, reused by inbound/outbound dashboards (no table). */
export function HourBars({ data, keys, height = 220 }: { data: Array<Record<string, number | string>>; keys: { key: string; name: string; color: string }[]; height?: number }) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 6, right: 12, bottom: 0, left: -12 }}>
        <CartesianGrid stroke="rgba(255,255,255,.06)" vertical={false} />
        <XAxis dataKey="hour" tick={{ stroke: '#5d6f66', fontSize: 11 }} tickLine={false} axisLine={{ stroke: '#2a473b' }} />
        <YAxis tick={{ stroke: '#5d6f66', fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} />
        <Tooltip contentStyle={{ background: '#0c2a1e', border: '1px solid rgba(255,255,255,.12)', borderRadius: 8, fontSize: 12 }} labelStyle={{ color: '#d6e4dc' }} cursor={{ fill: 'rgba(255,255,255,.04)' }} />
        {keys.map((k) => (
          <Bar key={k.key} dataKey={k.key} name={k.name} fill={k.color} isAnimationActive={false} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  )
}
