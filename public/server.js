// openWCS public product site — Express + EJS (live at openwcs.ai).
// The marketing pages are rendered through one shared layout (views/layout.ejs); each page's body lives
// in views/pages/*.ejs and its <head> SEO in the hand-owned data/pages.json manifest. Routes are clean
// (/asrs, not /asrs.html); legacy *.html URLs 301 to their clean path. Static assets (styles.css,
// i18n.js, images, robots/sitemap, roadmap.md) are served from static/. Listens on process.env.PORT.
const path = require('path');
const express = require('express');
const expressLayouts = require('express-ejs-layouts');
const compression = require('compression');

const pages = require('./data/pages.json');
const { isConfigured, sendContactEmail } = require('./services/graph');

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', true); // Hostinger / any reverse proxy in front
app.use(compression());

app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(expressLayouts);
app.set('layout', 'layout');

// Lightweight security headers. No CSP — the pages load Google Fonts and the i18n bundle inline-ish,
// and a wrong CSP silently breaks the site; add one deliberately later if wanted.
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('X-Frame-Options', 'SAMEORIGIN');
  next();
});

// Static assets at the site root (so the pages' relative links — favicon.png, openwcs.png, styles.css,
// i18n.js, roadmap.md — resolve unchanged).
app.use(express.static(path.join(__dirname, 'static'), {
  maxAge: '1h',
  setHeaders(res, filePath) {
    if (/\.(css|js)$/.test(filePath)) {
      // styles.css / i18n.js change on every deploy. Serve them with revalidation (ETag/
      // Last-Modified → 304 when unchanged) so a deploy is picked up on the next load instead
      // of being masked by the 1h-cached old copy — that staleness made the grouped nav and the
      // contact modal look broken until a hard refresh.
      res.setHeader('Cache-Control', 'no-cache');
    } else if (/\.(md|xml|txt)$/.test(filePath)) {
      res.setHeader('Cache-Control', 'public, max-age=300');
    }
  },
}));

// --- Contact form API -------------------------------------------------------
// JSON endpoint backing the public "Contact us" form. Sends mail via Microsoft
// Graph (see services/graph.js). Mounted with its own express.json() so static
// serving stays untouched, and placed BEFORE the page routes / 404 so it isn't
// swallowed by the catch-all.
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const RATE_LIMIT_MAX = 5;
const RATE_LIMIT_WINDOW_MS = 10 * 60 * 1000; // 10 minutes
const contactHits = new Map(); // ip -> [timestamps]
let warnedUnconfigured = false;

function rateLimited(ip) {
  const now = Date.now();
  const cutoff = now - RATE_LIMIT_WINDOW_MS;
  // Prune stale entries to keep the Map bounded.
  for (const [key, times] of contactHits) {
    const kept = times.filter((t) => t > cutoff);
    if (kept.length) contactHits.set(key, kept);
    else contactHits.delete(key);
  }
  const recent = (contactHits.get(ip) || []).filter((t) => t > cutoff);
  if (recent.length >= RATE_LIMIT_MAX) return true;
  recent.push(now);
  contactHits.set(ip, recent);
  return false;
}

app.post('/api/contact', express.json({ limit: '16kb' }), async (req, res) => {
  const body = req.body || {};

  // Honeypot: real users never fill `company`; bots that do are silently absorbed.
  if (typeof body.company === 'string' && body.company.trim() !== '') {
    return res.status(200).json({ ok: true });
  }

  const email = typeof body.email === 'string' ? body.email.trim() : '';
  const message = typeof body.message === 'string' ? body.message.trim() : '';
  const name = typeof body.name === 'string' ? body.name.trim() : '';

  if (!email || email.length > 200 || !EMAIL_RE.test(email)) {
    return res.status(400).json({ ok: false, error: 'A valid email is required.' });
  }
  if (!message || message.length < 1 || message.length > 5000) {
    return res.status(400).json({ ok: false, error: 'A message of 1 to 5000 characters is required.' });
  }
  if (name.length > 120) {
    return res.status(400).json({ ok: false, error: 'Name is too long.' });
  }

  if (rateLimited(req.ip)) {
    return res.status(429).json({ ok: false, error: 'Too many messages, please try again later.' });
  }

  if (!isConfigured()) {
    if (!warnedUnconfigured) {
      console.warn('Contact form received a submission but Microsoft Graph is not configured (MS_GRAPH_* / CONTACT_TO).');
      warnedUnconfigured = true;
    }
    return res.status(503).json({ ok: false, error: 'Contact form is not configured.' });
  }

  try {
    await sendContactEmail({ fromEmail: email, name: name || undefined, message });
    return res.status(200).json({ ok: true });
  } catch (err) {
    // Log the real error server-side; never leak Graph internals to the client.
    console.error('Contact form send failed:', err);
    return res.status(500).json({ ok: false, error: 'Could not send your message right now.' });
  }
});

// --- Live-demo SPA (same-origin sandbox) ------------------------------------
// The /live-demo marketing page embeds the real app as a read-only, in-browser sandbox via
// <iframe src="/demo-app/">. The demo bundle is built from the ui/ workspace but COMMITTED into
// public/static/demo/ so it ships with the public/ folder: the openwcs.ai host serves only public/
// and has no ui build toolchain, so it cannot build the bundle itself. Rebuild and re-commit it
// whenever the app changes in a way the demo shows (see keep-demo-updated):
//     npm run build:demo-app   (from public/, runs the ui build then copies the bundle in here)
// If the bundle is ever missing, /demo-app/ returns 404 and the /live-demo page falls back to its
// "open the full demo box" link, so the page still renders cleanly.
const demoDir = path.join(__dirname, 'static', 'demo');
app.use('/demo-app', express.static(demoDir, { maxAge: '1h', index: 'index.html' }));
// SPA deep-link fallback: any /demo-app/* path the static middleware did not resolve to a real file
// returns the demo index.html so client-side routing handles it. Skipped if the bundle is absent.
app.get('/demo-app/*', (req, res, next) => {
  res.sendFile(path.join(demoDir, 'index.html'), (err) => {
    if (err) next(); // bundle not built/copied yet → fall through to the 404 handler
  });
});

// Legacy .html URLs (the old static-export scheme) 301 to their clean path — so old inbound links,
// bookmarks and the retired GitHub Pages mirror keep resolving. /index.html → /, /asrs.html → /asrs.
// The query string is preserved; fragments are client-side and ride along automatically.
app.use((req, res, next) => {
  if (req.path.endsWith('.html')) {
    const clean = req.path === '/index.html' ? '/' : req.path.replace(/\.html$/, '');
    return res.redirect(301, clean + req.url.slice(req.path.length));
  }
  next();
});

// One clean route per page, from the manifest (data/pages.json).
for (const [route, p] of Object.entries(pages)) {
  app.get(route, (req, res) => {
    res.render(p.view, { headMeta: p.headMeta, scripts: p.scripts, bodyId: p.bodyId });
  });
}

// 404
app.use((req, res) => {
  res.status(404).render('pages/404', {
    headMeta: '<title>Page not found — openWCS</title>\n  <meta name="robots" content="noindex" />\n  <link rel="icon" type="image/png" href="/favicon.png" />',
    scripts: '',
    bodyId: 'top',
  });
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`openWCS public site listening on :${port}`));
