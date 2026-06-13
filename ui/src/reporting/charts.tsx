// Shared presentation pieces for the reporting screens: recharts wrappers (daily line/area chart
// with optional dashed forecast continuation, stacked bars), an inline SVG sparkline, the
// hour-of-day heat strip, heat legend, stat chips, and the honest empty/loading states.
import type { CSSProperties, ReactNode } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { HEAT_GRADIENT, heatColor, logT } from './heat'
import { useT } from '../i18n/useT'

export const CHART_COLORS = {
  lime: '#8DC63F',
  blue: '#5aa9ff',
  amber: '#f4b860',
  red: '#ff6b5e',
  violet: '#b39ddb',
  teal: '#56b3b4',
  grey: '#7d8a82',
}

const AXIS = { stroke: '#5d6f66', fontSize: 11 }
const TOOLTIP_STYLE: CSSProperties = {
  background: '#0c2a1e',
  border: '1px solid rgba(255,255,255,.12)',
  borderRadius: 8,
  fontSize: 12,
}

/** "MM-DD" tick for YYYY-MM-DD days (keeps the axis readable over 90 days). */
export function dayTick(day: string): string {
  return typeof day === 'string' && day.length >= 10 ? day.slice(5, 10) : String(day)
}

// ---------------------------------------------------------------- layout

export function ChartCard({
  title,
  subtitle,
  children,
  right,
  minWidth = 340,
}: {
  title: string
  subtitle?: string
  children: ReactNode
  right?: ReactNode
  minWidth?: number
}) {
  return (
    <div className="glass" style={{ padding: '1rem 1.1rem', flex: `1 1 ${minWidth}px`, minWidth: 0 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '.75rem' }}>
        <div style={{ marginBottom: '.6rem' }}>
          <h3 style={{ margin: 0, fontSize: '.95rem' }}>{title}</h3>
          {subtitle && (
            <p className="muted" style={{ margin: '.15rem 0 0', fontSize: '.75rem' }}>
              {subtitle}
            </p>
          )}
        </div>
        {right}
      </div>
      {children}
    </div>
  )
}

export function StatChip({ label, value, color }: { label: string; value: ReactNode; color?: string }) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '.1rem',
        padding: '.35rem .7rem',
        borderRadius: 8,
        border: '1px solid var(--glass-border)',
        background: 'rgba(255,255,255,.02)',
        minWidth: 78,
      }}
    >
      <span style={{ fontSize: '1.25rem', fontWeight: 600, color: color ?? 'var(--text)', lineHeight: 1.2 }}>
        {value}
      </span>
      <span
        className="muted"
        style={{ fontFamily: 'var(--font-mono)', fontSize: '.6rem', letterSpacing: '.1em', textTransform: 'uppercase' }}
      >
        {label}
      </span>
    </div>
  )
}

/** Honest empty state: report history only accumulates from the day the system was deployed. */
export function EmptyHistoryNote({ what }: { what: string }) {
  const t = useT('reporting')
  return (
    <div style={{ padding: '1.6rem 1rem', textAlign: 'center', color: 'var(--text-dim)', fontSize: '.85rem' }}>
      {t('emptyHistoryPrefix', 'No')} {what} {t(
        'emptyHistoryRest',
        'in this window yet. Report history accumulates from deployment day, so a freshly deployed system starts empty and fills up as the warehouse runs.',
      )}
    </div>
  )
}

export function LoadingNote({ children }: { children?: ReactNode }) {
  const t = useT('reporting')
  return (
    <div style={{ padding: '1.6rem 1rem', textAlign: 'center', color: 'var(--text-dim)' }}>
      {children ?? t('loadingReport', 'Loading report…')}
    </div>
  )
}

// ---------------------------------------------------------------- recharts wrappers

export interface DailySeries {
  key: string
  name: string
  color: string
  /** Dashed (used for the 14-day forecast continuation). */
  dashed?: boolean
}

/**
 * Daily line chart over rows keyed by `day` (YYYY-MM-DD). Forecast series simply carry values on
 * future days and `dashed: true`: rows where a series has no value (undefined) draw no point.
 */
export function DailyChart({
  data,
  series,
  height = 240,
  yTick,
}: {
  data: Array<Record<string, number | string | undefined>>
  series: DailySeries[]
  height?: number
  yTick?: (v: number) => string
}) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={data} margin={{ top: 6, right: 12, bottom: 0, left: -12 }}>
        <CartesianGrid stroke="rgba(255,255,255,.06)" vertical={false} />
        <XAxis dataKey="day" tickFormatter={dayTick} tick={AXIS} tickLine={false} axisLine={{ stroke: '#2a473b' }} minTickGap={28} />
        <YAxis tick={AXIS} tickLine={false} axisLine={false} tickFormatter={yTick} allowDecimals={false} />
        <Tooltip contentStyle={TOOLTIP_STYLE} labelStyle={{ color: '#d6e4dc' }} cursor={{ stroke: 'rgba(255,255,255,.2)' }} />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        {series.map((s) => (
          <Line
            key={s.key}
            type="monotone"
            dataKey={s.key}
            name={s.name}
            stroke={s.color}
            strokeWidth={2}
            strokeDasharray={s.dashed ? '6 4' : undefined}
            dot={false}
            connectNulls={false}
            isAnimationActive={false}
          />
        ))}
      </LineChart>
    </ResponsiveContainer>
  )
}

/** Stacked bars over arbitrary x values (days, devices, …). */
export function StackedBars({
  data,
  xKey,
  series,
  height = 240,
  xTick,
}: {
  data: Array<Record<string, number | string | undefined>>
  xKey: string
  series: DailySeries[]
  height?: number
  xTick?: (v: string) => string
}) {
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 6, right: 12, bottom: 0, left: -12 }}>
        <CartesianGrid stroke="rgba(255,255,255,.06)" vertical={false} />
        <XAxis dataKey={xKey} tickFormatter={xTick} tick={AXIS} tickLine={false} axisLine={{ stroke: '#2a473b' }} minTickGap={24} />
        <YAxis tick={AXIS} tickLine={false} axisLine={false} allowDecimals={false} />
        <Tooltip contentStyle={TOOLTIP_STYLE} labelStyle={{ color: '#d6e4dc' }} cursor={{ fill: 'rgba(255,255,255,.04)' }} />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        {series.map((s) => (
          <Bar key={s.key} dataKey={s.key} name={s.name} stackId="stack" fill={s.color} isAnimationActive={false} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  )
}

// ---------------------------------------------------------------- sparkline (table mini chart)

/** Tiny inline SVG line of a value series: the per-scanner error-rate mini chart. */
export function Sparkline({
  values,
  width = 110,
  height = 26,
  color = CHART_COLORS.lime,
}: {
  values: number[]
  width?: number
  height?: number
  color?: string
}) {
  if (values.length < 2) {
    return <span className="muted" style={{ fontSize: '.7rem' }}>-</span>
  }
  const max = Math.max(...values, 0.0001)
  const pad = 2
  const pts = values
    .map((v, i) => {
      const x = pad + (i / (values.length - 1)) * (width - 2 * pad)
      const y = height - pad - (Math.max(0, v) / max) * (height - 2 * pad)
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
  return (
    <svg width={width} height={height} aria-hidden="true" style={{ display: 'block' }}>
      <polyline points={pts} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" />
    </svg>
  )
}

// ---------------------------------------------------------------- hour-of-day heat strip

/**
 * The "day map": the window's events compiled into the 24 hours of the day as one heat strip.
 * Peak hours (≥ 85 % of the busiest hour) get a lime outline so the peaks are unmissable.
 */
export function HourOfDayStrip({ counts, caption }: { counts: number[]; caption?: string }) {
  const t = useT('reporting')
  const byHour = Array.from({ length: 24 }, (_, h) => counts[h] ?? 0)
  const max = Math.max(...byHour)
  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(24, 1fr)', gap: 3 }}>
        {byHour.map((v, h) => {
          const t = logT(v, max)
          const peak = max > 0 && v >= max * 0.85
          return (
            <div key={h} title={`${String(h).padStart(2, '0')}:00: ${v}`} style={{ textAlign: 'center' }}>
              <div
                style={{
                  height: 34,
                  borderRadius: 4,
                  background: v > 0 ? heatColor(t) : 'rgba(255,255,255,.04)',
                  opacity: v > 0 ? 0.45 + 0.55 * t : 1,
                  border: peak ? '1.5px solid var(--herbal-lime)' : '1px solid rgba(255,255,255,.06)',
                  boxSizing: 'border-box',
                }}
              />
              <span className="muted" style={{ fontSize: '.55rem', fontFamily: 'var(--font-mono)' }}>
                {h % 3 === 0 ? h : ' '}
              </span>
            </div>
          )
        })}
      </div>
      {caption && (
        <p className="muted" style={{ margin: '.3rem 0 0', fontSize: '.72rem' }}>
          {caption} {t('peakHoursNote', 'Outlined cells are peak hours (≥ 85 % of the busiest hour).')}
        </p>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- heat legend

export function HeatLegend({ min, max, unit }: { min: number; max: number; unit: string }) {
  const t = useT('reporting')
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '.5rem', fontSize: '.72rem', color: 'var(--text-dim)' }}>
      <span>{min}</span>
      <span
        aria-hidden="true"
        style={{ width: 120, height: 10, borderRadius: 5, background: HEAT_GRADIENT, display: 'inline-block' }}
      />
      <span>
        {max} {unit} {t('logScaleSuffix', '(log scale)')}
      </span>
    </div>
  )
}
