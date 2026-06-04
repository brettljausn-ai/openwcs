import { useRef, useState } from 'react'
import { createPortal } from 'react-dom'

/**
 * A small "?" affordance next to a field label that reveals a styled hover/focus popover explaining
 * the field, with an optional concrete example. Portalled to <body> so it is never clipped by a
 * dialog's overflow or a card's backdrop-filter stacking context.
 *
 * Usage:  <label>Cadence (days) <InfoTip text="How often this SKU is counted." example="30" /></label>
 */
export default function InfoTip({ text, example }: { text: string; example?: string }) {
  const ref = useRef<HTMLButtonElement>(null)
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)

  function show() {
    const r = ref.current?.getBoundingClientRect()
    if (!r) return
    // Prefer below-right of the icon; clamp to the viewport so it stays on screen.
    const width = 280
    const left = Math.min(r.left, window.innerWidth - width - 12)
    setPos({ top: r.bottom + 6, left: Math.max(8, left) })
  }
  const hide = () => setPos(null)

  return (
    <>
      <button
        ref={ref}
        type="button"
        className="info-tip"
        aria-label="What is this?"
        onMouseEnter={show}
        onMouseLeave={hide}
        onFocus={show}
        onBlur={hide}
        onClick={(e) => {
          e.preventDefault()
          pos ? hide() : show()
        }}
      >
        ?
      </button>
      {pos &&
        createPortal(
          <div className="info-tip-pop" style={{ top: pos.top, left: pos.left }} role="tooltip">
            <div className="info-tip-text">{text}</div>
            {example && (
              <div className="info-tip-example">
                <span className="info-tip-eg">e.g.</span> {example}
              </div>
            )}
          </div>,
          document.body,
        )}
    </>
  )
}
