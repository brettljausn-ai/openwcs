# openWCS public product site

The public-facing marketing/product page for openWCS — positions it as the open, transparent
alternative to locked-in WCS apps from integrators.

- **`index.html`** + **`styles.css`** — a self-contained static site (no build step). The visual
  language follows the openWCS brand tokens (dark forest / herbal-lime / glass; see
  [`../styling.md`](../styling.md)).
- **Feature pages** (same static template, reuse `styles.css`):
  - **`automation.html`** — the Automation / equipment hub: a card grid linking the equipment
    families with **Built** / **Roadmap** status pills. **`conveyors.html`**, **`asrs.html`** and
    **`gtp.html`** are sub-pages of Automation (and link back to it); AMR and AutoStore are Roadmap
    cards (device adapters not built yet).
  - **`conveyors.html`** — deep dive on conveyor routing (incl. self-learning / topology discovery).
  - **`asrs.html`** — deep dive on ASRS slotting (incl. in-aisle behaviour and empty-HU management).
  - **`gtp.html`** — goods-to-person station execution: STOCK + ORDER/PUT_WALL nodes, present one
    stock HU → put-to-light put-list across many order destinations (the batch), ORDER_LOCATION vs
    PUT_WALL modes. **Built**.
  - **`architecture.html`** — a plain-language **Architecture** tour for non-technical buyers: where
    openWCS sits between the WMS/ERP and the floor, the small specialised services (master data,
    inventory, orders, allocation, slotting, process engine, flow-orchestrator, device adapters) over
    a shared event log, the end-to-end journey of a unit of work (goods-in → slotting → storage →
    order → allocation → pick/GTP → dispatch), and the gateway/security + event-log concepts. Linked
    from the index nav (`navArch` → `architecture.html`) and the homepage architecture section
    (`archMore`). Built with HTML/CSS visuals only (`.layer-stack`, `.archmap`, `.steps.journey`).
  - **`functions.html`** — the Function overview hub: a card grid linking every function (foundations
    + storage/movement + roadmap) with a one-line description and a **Built** / **Roadmap** status
    pill (`.pill .pill-built` / `.pill .pill-roadmap` in `styles.css`).
  - Foundational/built functions: **`process-designer.html`** (admin-designed BPMN on Flowable),
    **`inventory.html`** (event-sourced stock), **`allocation.html`** (allocation + cubing + batch),
    **`host-api.html`** (canonical vendor-neutral Host API), **`security.html`** (gateway JWT + RBAC +
    Keycloak), plus **`slotting.html`** and **`replenishment.html`**.
  - Roadmap functions (lead with a `Roadmap` pill + a "planned — not yet implemented" note):
    **`picking.html`** (planning is built; execution is roadmap), **`cycle-counting.html`**,
    **`kpi-dashboards.html`**, **`hardware-visualisation.html`**.
  - **`roadmap.html`** + **`roadmap.md`** — the Roadmap page. `roadmap.html` is a thin shell that
    fetches **`roadmap.md`** at runtime and draws a modern timeline from it (vanilla JS, no build,
    no markdown library). **`roadmap.md` is the single source of truth** — it's plain text with a
    self-documenting header: `## Heading` starts a timeline phase, an optional `> caption` adds a
    subtitle, and each `- [status] Title :: description` line is one item (`status` ∈ `done` /
    `active` / `planned` / `exploring`). Edit `roadmap.md` to change the roadmap; the page picks it up
    automatically. The page chrome is translated via `rm.*` keys in `i18n.js`; the item text itself
    stays in `roadmap.md` (one language, one source). Linked from the nav on every top-level page.
  - Honesty rule: never present a roadmap capability as existing. Keep each function's `Built` /
    `Roadmap` tag accurate to what's actually in the codebase.
- **`favicon.png`** — browser/tab icon.
- **`Logo_white_solo.png`** — the white wordmark logo shown in the nav bar.
- **`openwcs.png`** — the hero product image, and the Open Graph / Twitter social-share image.
- **`robots.txt`** + **`sitemap.xml`** — SEO. `index.html` carries the canonical URL, description,
  Open Graph / Twitter cards, and `SoftwareApplication` JSON-LD. If the public URL ever changes,
  update the absolute URLs across those three files together, and bump `<lastmod>` in `sitemap.xml`
  on meaningful content changes.
- Open `index.html` directly, or serve the folder: `python3 -m http.server -d public 8088`.
- Deploy anywhere static: GitHub Pages, Netlify, S3/CloudFront, etc.

## Auto-deploy (GitHub Pages)

[`.github/workflows/pages.yml`](../.github/workflows/pages.yml) publishes this folder to GitHub
Pages on every push to `main` that touches `public/**` (and on demand via *Run workflow*). Once it
has run, the site is live at **https://brettljausn-ai.github.io/openwcs/**. The Pages **Source**
must be set to **GitHub Actions** under *Settings → Pages* (build type `workflow`) — if it's left
on "Deploy from a branch", Pages serves a Jekyll render of the repo `README.md` instead of this
site. Links and assets are relative, so the page works under the project subpath.

## Keep it current

**This page must track real product capabilities** — update it alongside the code and the
[wiki](https://github.com/brettljausn-ai/openwcs/wiki) whenever a feature lands that changes what
openWCS can do (the capabilities grid, the comparison table, or the architecture diagram). Keep
the claims accurate to what's actually built.

**Keep the roadmap current too** — when a capability's status changes (a roadmap item ships, work
starts on it, or new planned work is decided), update the matching `- [status] Title` line in
[`roadmap.md`](roadmap.md) in the same change. The CI docs agent
([`scripts/docs-agent.sh`](../scripts/docs-agent.sh)) is instructed to do this on every PR, but it
applies whoever is editing: `roadmap.md` is the roadmap, so it must track reality, never mark
something `done` before it's built end-to-end.
