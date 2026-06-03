# openWCS public product site

The public-facing marketing/product page for openWCS — positions it as the open, transparent
alternative to locked-in WCS apps from integrators.

- **`index.html`** + **`styles.css`** — a self-contained static site (no build step). The visual
  language follows the openWCS brand tokens (dark forest / herbal-lime / glass; see
  [`../styling.md`](../styling.md)).
- **Feature pages** (same static template, reuse `styles.css`):
  - **`functions.html`** — the Function overview hub: a card grid linking every function with a
    one-line description and a **Built** / **Roadmap** status pill (`.pill .pill-built` /
    `.pill .pill-roadmap` in `styles.css`).
  - **`conveyors.html`**, **`asrs.html`** — deep dives on conveyor routing (incl. self-learning /
    topology discovery) and ASRS slotting (incl. in-aisle behaviour and empty-HU management).
  - Built functions: **`slotting.html`**, **`replenishment.html`**.
  - Roadmap functions (lead with a `Roadmap` pill + a "planned — not yet implemented" note):
    **`picking.html`** (planning is built; execution is roadmap), **`cycle-counting.html`**,
    **`kpi-dashboards.html`**, **`hardware-visualisation.html`**.
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
