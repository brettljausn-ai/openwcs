# openWCS public product site (Express + EJS)

The public-facing marketing/product site for openWCS. It used to be ~20 standalone static HTML pages;
it's now an **Express + EJS** app so it can deploy to a Node host (Hostinger), with every page rendered
through **one shared layout** — the header/nav/lang-switcher/footer live in a single place instead of
being copy-pasted across pages. Each page's per-page SEO (`<title>`, description, canonical, OG/Twitter,
JSON-LD) and i18n stay exactly as before.

## How it's structured

```
public/
  server.js              Express app (routes from data/pages.json, static assets, 404). Listens on $PORT.
  views/
    layout.ejs           The one shared shell: <head> boilerplate + header + footer. Per-page bits are locals.
    pages/*.ejs          One body view per page (the content between header and footer).
  data/pages.json        Generated manifest: route → { view, headMeta, navLinks, scripts, bodyId }.
  static/                Served at the site root: styles.css, i18n.js, images, robots.txt, sitemap.xml, roadmap.md
  src-html/              The legacy source pages — the editable source for the body + per-page <head>.
  scripts/
    convert.js           Regenerates views/pages/*.ejs + data/pages.json from src-html/  (npm run build:pages)
    build-static.js      Pre-renders the whole site to dist/ as plain HTML            (npm run build:static)
```

Asset and inter-page links are **relative** (`styles.css`, `asrs.html`), so the same output works both at
a domain root (Express/Hostinger) and under the GitHub Pages subpath (`/openwcs/`).

## Run locally

```
cd public
npm install
npm start            # http://localhost:3000  (PORT overrides)
```

## Editing content

The editable source is **`src-html/*.html`** (full pages) — edit there, then regenerate the views:

```
npm run build:pages
```

`views/pages/*.ejs` and `data/pages.json` are generated **and committed** (so the app runs on a host with
no build step). Re-run `build:pages` after any `src-html/` change and commit the result.

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
4. **Update the absolute URLs to your domain**: the canonical/OG/sitemap URLs still say
   `https://brettljausn-ai.github.io/openwcs/...`. Search-and-replace that origin across `src-html/*.html`
   and `static/sitemap.xml` to your Hostinger domain, then `npm run build:pages` and commit.

Any other Node host (Render, Fly, a VPS with `pm2 start server.js`, Docker) works the same — it just needs
`npm install` + `npm start` and a port.

### GitHub Pages (free static mirror)

[`.github/workflows/pages.yml`](../.github/workflows/pages.yml) runs `npm run build:static` on pushes to
`main` that touch `public/**` and publishes `public/dist/` to Pages — **https://brettljausn-ai.github.io/openwcs/**.
Pages **Source** must be **GitHub Actions** (Settings → Pages, build type `workflow`).
