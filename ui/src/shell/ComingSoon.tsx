// Placeholder for screens reserved in the permission catalog but built by a follow-up agent.
// Each reserved screen has its own component file (so an agent can replace just that file);
// until then it renders this on-brand stub.
export default function ComingSoon({ title, note }: { title: string; note?: string }) {
  return (
    <div className="app-content">
      <div className="page-head">
        <div className="eyebrow">openWCS</div>
        <h1>{title}</h1>
        <p>{note || 'This screen is being built.'}</p>
      </div>
      <div className="glass card-pad" style={{ maxWidth: 560 }}>
        <p className="muted" style={{ margin: 0 }}>
          Coming soon — the backend is in place and this screen is on the build list.
        </p>
      </div>
    </div>
  )
}
