// openWCS public product site — Express + EJS.
// The 20 marketing pages are rendered through one shared layout (views/layout.ejs); each page's
// per-page <head> SEO, contextual nav and body live in views/pages/*.ejs + data/pages.json (generated
// by `npm run build:pages`). Static assets (styles.css, i18n.js, images, robots/sitemap, roadmap.md)
// are served from static/. Designed for any Node host — listens on process.env.PORT (Hostinger).
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

// One route per page, from the generated manifest.
for (const [route, p] of Object.entries(pages)) {
  app.get(route, (req, res) => {
    res.render(p.view, { headMeta: p.headMeta, navLinks: p.navLinks, scripts: p.scripts, bodyId: p.bodyId });
  });
}

// /index.html → the canonical "/".
app.get('/index.html', (req, res) => res.redirect(301, '/'));

// 404
app.use((req, res) => {
  res.status(404).render('pages/404', {
    headMeta: '<title>Page not found — openWCS</title>\n  <meta name="robots" content="noindex" />\n  <link rel="icon" type="image/png" href="favicon.png" />',
    navLinks: '<a href="/">← Home</a>',
    scripts: '',
    bodyId: 'top',
  });
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`openWCS public site listening on :${port}`));
