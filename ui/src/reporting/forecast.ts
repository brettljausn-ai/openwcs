// Pure forecasting / trend helpers for the reporting screens. No fetches, no React: every
// function maps plain inputs to plain outputs so behaviour is obvious and unit-testable.
//
// The forecast model is a weekday-seasonal moving average: the prediction for a future day is the
// average of the SAME WEEKDAY over the trailing weeks of history (warehouse volume is strongly
// weekly-periodic: Mondays look like Mondays). When a weekday has no history yet, it falls back to
// the overall mean. Honest and simple: no extrapolated trends pretending to be more.

export interface DayPoint {
  day: string // YYYY-MM-DD
  value: number
}

/** Max trailing same-weekday observations averaged per forecast day. */
const WEEKS_LOOKBACK = 8

/** Parse YYYY-MM-DD as a UTC timestamp (avoids local-timezone day drift). */
function dayMs(day: string): number {
  const [y, m, d] = day.split('-').map(Number)
  return Date.UTC(y, (m || 1) - 1, d || 1)
}

/** YYYY-MM-DD for a UTC timestamp. */
function msDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10)
}

/** day + n calendar days, in YYYY-MM-DD (UTC-safe). */
export function addDays(day: string, n: number): string {
  return msDay(dayMs(day) + n * 86_400_000)
}

/** 0=Sunday … 6=Saturday for a YYYY-MM-DD day. */
function weekday(day: string): number {
  return new Date(dayMs(day)).getUTCDay()
}

/**
 * Continue a daily series `horizon` days past its last point. For each future day: the mean of the
 * last `WEEKS_LOOKBACK` history values on the same weekday; if that weekday has no history, the
 * overall mean. An empty history yields an empty forecast (nothing honest to predict from).
 */
export function forecastDaily(history: DayPoint[], horizon = 14): DayPoint[] {
  const sorted = [...history].sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0))
  if (sorted.length === 0) return []

  const overallMean = sorted.reduce((s, p) => s + p.value, 0) / sorted.length
  // Per weekday: values in chronological order (we average the most recent WEEKS_LOOKBACK).
  const byWeekday = new Map<number, number[]>()
  for (const p of sorted) {
    const wd = weekday(p.day)
    const list = byWeekday.get(wd) ?? []
    list.push(p.value)
    byWeekday.set(wd, list)
  }

  const lastDay = sorted[sorted.length - 1].day
  const out: DayPoint[] = []
  for (let i = 1; i <= horizon; i++) {
    const day = addDays(lastDay, i)
    const seen = byWeekday.get(weekday(day))
    const window = seen && seen.length > 0 ? seen.slice(-WEEKS_LOOKBACK) : null
    const value = window ? window.reduce((s, v) => s + v, 0) / window.length : overallMean
    out.push({ day, value })
  }
  return out
}

/**
 * Least-squares slope of a series over its index (units: value per step/day). Used to detect a
 * rising error rate on a scanner. Fewer than two points → 0 (no trend claimable).
 */
export function regressionSlope(values: number[]): number {
  const n = values.length
  if (n < 2) return 0
  const meanX = (n - 1) / 2
  const meanY = values.reduce((s, v) => s + v, 0) / n
  let num = 0
  let den = 0
  for (let i = 0; i < n; i++) {
    num += (i - meanX) * (values[i] - meanY)
    den += (i - meanX) * (i - meanX)
  }
  return den === 0 ? 0 : num / den
}
