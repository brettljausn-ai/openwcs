// Small formatting helpers shared across the dashboards.

/** "16:30" from an ISO instant, or a dash. Used for projected-completion / cutoff times. */
export function timeHm(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/** Minutes as a compact "1h 20m" / "45m" string. */
export function minsLabel(min: number): string {
  if (!Number.isFinite(min) || min < 0) return '—'
  const m = Math.round(min)
  if (m < 60) return `${m}m`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

/** Seconds as "mm:ss" / "h:mm:ss" countdown; negative clamps to 0 (cutoff passed). */
export function countdown(secs: number): string {
  const s = Math.max(0, Math.floor(secs))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${m}:${pad(sec)}`
}

/** A safe percentage 0..100, rounded to one decimal. */
export function pct1(n: number): string {
  if (!Number.isFinite(n)) return '0'
  return (Math.round(n * 10) / 10).toString()
}
