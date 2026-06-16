import { useT } from '../i18n/useT'

// Full-screen "this operator process isn't available yet" placeholder for handheld processes that
// have no flow built (Goods In, Packing). Honest: it does not fake any functionality. Big, calm,
// glove-friendly, fits a small handheld viewport.
export default function ProcessComingSoon({ title }: { title: string }) {
  const t = useT('operator')
  return (
    <div className="op-coming">
      <div className="op-coming-icon" aria-hidden="true">⏳</div>
      <h1 className="op-coming-title">{title}</h1>
      <p className="op-coming-note">
        {t('comingSoonBody', 'This process is not yet available on the handheld. It is on the build list.')}
      </p>
    </div>
  )
}
