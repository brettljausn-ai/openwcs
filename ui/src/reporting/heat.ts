// Shared heat-ramp utilities for the reporting heatmaps (3D conveyor/rack tints, hour-of-day
// strips, HTML legends). Pure colour math: usable from both the React DOM and the three.js chunk.

/** Colour stops of the heat ramp, cold → hot (kept theme-adjacent: dim green → yellow → red). */
export const HEAT_STOPS = ['#2f7d57', '#b9c94b', '#f0a33c', '#ff5544']

function hexToRgb(hex: string): [number, number, number] {
  const v = parseInt(hex.slice(1), 16)
  return [(v >> 16) & 255, (v >> 8) & 255, v & 255]
}

function rgbToHex(r: number, g: number, b: number): string {
  const c = (n: number) => Math.round(Math.max(0, Math.min(255, n))).toString(16).padStart(2, '0')
  return `#${c(r)}${c(g)}${c(b)}`
}

/** Heat colour for t in [0,1], linearly interpolated across HEAT_STOPS. */
export function heatColor(t: number): string {
  const x = Math.max(0, Math.min(1, t)) * (HEAT_STOPS.length - 1)
  const i = Math.min(HEAT_STOPS.length - 2, Math.floor(x))
  const f = x - i
  const [r1, g1, b1] = hexToRgb(HEAT_STOPS[i])
  const [r2, g2, b2] = hexToRgb(HEAT_STOPS[i + 1])
  return rgbToHex(r1 + (r2 - r1) * f, g1 + (g2 - g1) * f, b1 + (b2 - b1) * f)
}

/** CSS gradient of the full ramp, for HTML legends. */
export const HEAT_GRADIENT = `linear-gradient(90deg, ${HEAT_STOPS.join(', ')})`

/** Log-scale normalisation: value → t in [0,1] given the window maximum (0 when max is 0). */
export function logT(value: number, max: number): number {
  if (max <= 0 || value <= 0) return 0
  return Math.log1p(value) / Math.log1p(max)
}
