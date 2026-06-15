# openWCS public product site (Express + EJS)

The public-facing marketing/product site for openWCS, live at **https://openwcs.ai**. It's an
**Express + EJS** app deployed to a Node host (Hostinger), with every page rendered through **one shared
layout** — the header/nav/lang-switcher/footer live in a single place instead of being copy-pasted across
pages. URLs are **clean** (`/asrs`, not `/asrs.html`); each page's per-page SEO (`<title>`, description,
canonical, OG/Twitter, JSON-LD) and i18n live in the manifest + views.

## How it's structured

```
public/
  server.js              Express app: clean page routes from data/pages.json, legacy *.html -> clean 301s,
                         the /api/contact endpoint, static assets, 404. Listens on $PORT.
  views/
    layout.ejs           The one shared shell: <head> boilerplate + header + footer. Per-page bits are locals.
    pages/*.ejs          One body view per page (the content between header and footer). SOURCE OF TRUTH.
  data/pages.json        Hand-owned manifest: cleanRoute -> { view, headMeta, scripts, bodyId }.
  static/                Served at the site root: styles.css, i18n.js, images, robots.txt, sitemap.xml, roadmap.md
  pages-redirect/        index.html + 404.html — the retired GitHub Pages mirror's redirect to openwcs.ai.
  scripts/
    build-static.js      Pre-renders the whole site to dist/ as plain HTML (clean dirs)  (npm run build:static)
```

Inter-page and asset links are **root-absolute** (`/asrs`, `/#why`, `/styles.css`), so they resolve
correctly from any route or trailing slash and point straight at the clean URL — no redirect hop, no
lost `#anchor`. (This replaced the old relative `.html` links, which 301-bounced and sometimes dropped
fragments.)

## Run locally

```
cd public
npm install
npm start            # http://localhost:3000  (PORT overrides)
```

## Editing content

Edit the page body directly in **`views/pages/<name>.ejs`** and its per-page `<head>` SEO in the matching
entry of **`data/pages.json`** (`headMeta`). To add a page: create `views/pages/<name>.ejs`, add a
`"/<name>": { "view": "pages/<name>", "headMeta": "…", "bodyId": "top" }` entry to `data/pages.json`, and
link to `/<name>`. No build step — the app renders straight from these files. Keep internal links
**root-absolute** (`/<name>`, `/#anchor`); the legacy `*.html` URLs 301 to clean automatically.

> **Keep it current** — this site must track real product capabilities. Update it alongside the code
> whenever a feature lands that changes what openWCS can do. Keep every function's **Built** / **Roadmap**
> tag accurate; never present a roadmap capability as existing.

**Roadmap** — `roadmap.html` fetches **`static/roadmap.md`** at runtime and draws the timeline from it.
`roadmap.md` is the single source of truth: `## Heading` starts a phase, `> caption` adds a subtitle, and
each `- [status] Title :: description` line is one item (`status` ∈ `done`/`active`/`planned`/`exploring`).
Edit `roadmap.md` to change the roadmap; the page picks it up automatically. Keep it accurate — never mark
something `done` before it's built end-to-end.

## Contact form

The site exposes a single JSON API endpoint, **`POST /api/contact`**, backing the public "Contact us"
form. It sends mail through **Microsoft Graph** using the OAuth2 **client-credentials** flow (the
sender is a fixed mailbox; the submitter is set as **reply-to**, so hitting Reply reaches them).

Request body: `{ email (required), message (required), name?, company? }`. Responses are
`{ ok: true }` on success or `{ ok: false, error }` otherwise (400 validation, 429 rate limit,
503 not configured, 500 send failure). `company` is a **honeypot** field (bots that fill it get a
silent 200), and submissions are **rate-limited per IP** (5 per 10 minutes).

It needs these env vars (see `.env.example`):

- `MS_GRAPH_TENANT_ID`, `MS_GRAPH_CLIENT_ID`, `MS_GRAPH_CLIENT_SECRET`, `MS_GRAPH_MAIL_ADDRESS` — required
- `MS_GRAPH_FROM_NAME` — optional sender display name (default `openWCS`)
- `CONTACT_TO` — optional recipient (default `contact@brettljausn.ai`)

Until all four required vars are set the endpoint returns `503`. This endpoint **only works on the Node
deploy** — the static GitHub Pages mirror has no server, so the form is inert there.

## Deploy

### Hostinger (Node.js app)

1. In hPanel → **Websites → … → Node.js** (or *Setup Node.js App*), create an app: **Node 18+**, application
   root = this `public/` folder, **startup file = `server.js`**.
2. Get the code there — push this repo / upload the `public/` folder (you do **not** need `node_modules/` or
   `dist/`; the host runs `npm install`). Then **Run NPM install** and **Start**.
3. The app listens on `process.env.PORT` (Hostinger assigns it) and `trust proxy` is on, so it works behind
   Hostinger's reverse proxy. Point your domain at the app.
4. **Absolute URLs** (canonical/OG/sitemap) use `https://openwcs.ai`. To host under a different domain,
   search-and-replace that origin across `data/pages.json` (the `headMeta` SEO tags) and
   `static/sitemap.xml` + `static/robots.txt`, then commit.

Any other Node host (Render, Fly, a VPS with `pm2 start server.js`, Docker) works the same — it just needs
`npm install` + `npm start` and a port.

### GitHub Pages (redirect to openwcs.ai)

The live site is **https://openwcs.ai** (Express + EJS on a Node host). The old free GitHub Pages
mirror (**https://brettljausn-ai.github.io/openwcs/**) is **retired**: instead of mirroring the site,
[`.github/workflows/pages.yml`](../.github/workflows/pages.yml) publishes the tiny redirect bundle in
[`public/pages-redirect/`](./pages-redirect) — `index.html` + `404.html` bounce every legacy URL
(deep links and unknown paths alike) to the matching page on openwcs.ai, preserving the path under the
`/openwcs/` base. Pages **Source** must be **GitHub Actions** (Settings → Pages, build type `workflow`).

(`npm run build:static` still pre-renders the full site to `public/dist/` for any other static host;
it is just no longer what Pages serves.)
