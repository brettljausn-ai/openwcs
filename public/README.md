# openWCS public product site

The public-facing marketing/product page for openWCS — positions it as the open, transparent
alternative to locked-in WCS apps from integrators.

- **`index.html`** + **`styles.css`** — a self-contained static site (no build step). The visual
  language follows the openWCS brand tokens (dark forest / herbal-lime / glass; see
  [`../styling.md`](../styling.md)).
- Open `index.html` directly, or serve the folder: `python3 -m http.server -d public 8088`.
- Deploy anywhere static: GitHub Pages (point Pages at `/public`), Netlify, S3/CloudFront, etc.

## Keep it current

**This page must track real product capabilities** — update it alongside the code and the
[wiki](https://github.com/brettljausn-ai/openwcs/wiki) whenever a feature lands that changes what
openWCS can do (the capabilities grid, the comparison table, or the architecture diagram). Keep
the claims accurate to what's actually built.
