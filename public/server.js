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
    if (/\.(md|xml|txt)$/.test(filePath)) {
      res.setHeader('Cache-Control', 'public, max-age=300');
    }
  },
}));

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
