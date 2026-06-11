// Pre-render the Express site to a fully static dist/ — the same views + layout, rendered to plain
// HTML, plus the static assets copied in. Used by the GitHub Pages workflow so the free static mirror
// keeps working alongside the Node (Hostinger) deploy. Relative asset URLs make it valid under the
// Pages subpath (/openwcs/) as well as at a domain root.
const fs = require('fs');
const path = require('path');
const ejs = require('ejs');

const ROOT = path.join(__dirname, '..');
const VIEWS = path.join(ROOT, 'views');
const DIST = path.join(ROOT, 'dist');
const pages = require(path.join(ROOT, 'data', 'pages.json'));

async function renderPage(p) {
  const body = await ejs.renderFile(path.join(VIEWS, p.view + '.ejs'), {}, { async: true });
  return ejs.renderFile(path.join(VIEWS, 'layout.ejs'),
    { body, headMeta: p.headMeta, navLinks: p.navLinks, scripts: p.scripts, bodyId: p.bodyId },
    { async: true });
}

(async () => {
  fs.rmSync(DIST, { recursive: true, force: true });
  fs.mkdirSync(DIST, { recursive: true });
  for (const [route, p] of Object.entries(pages)) {
    const out = route === '/' ? 'index.html' : route.replace(/^\//, '');
    fs.writeFileSync(path.join(DIST, out), await renderPage(p));
  }
  fs.cpSync(path.join(ROOT, 'static'), DIST, { recursive: true }); // styles, i18n, images, robots, sitemap, roadmap.md
  console.log(`built static site -> dist/ (${Object.keys(pages).length} pages + assets)`);
})();
