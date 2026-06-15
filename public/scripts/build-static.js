// Pre-render the Express site to a fully static dist/ — the same views + layout, rendered to plain
// HTML, plus the static assets copied in. Kept for any plain static host (GitHub Pages itself now
// serves the redirect bundle in pages-redirect/, not this). Clean routes map to directory-index
// files (/asrs -> asrs/index.html) so the site's root-absolute links resolve at a domain root.
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
    { body, headMeta: p.headMeta, scripts: p.scripts, bodyId: p.bodyId },
    { async: true });
}

(async () => {
  fs.rmSync(DIST, { recursive: true, force: true });
  fs.mkdirSync(DIST, { recursive: true });
  for (const [route, p] of Object.entries(pages)) {
    // Clean route -> directory index: "/" -> index.html, "/asrs" -> asrs/index.html.
    const rel = route === '/' ? 'index.html' : path.join(route.replace(/^\//, ''), 'index.html');
    const outPath = path.join(DIST, rel);
    fs.mkdirSync(path.dirname(outPath), { recursive: true });
    fs.writeFileSync(outPath, await renderPage(p));
  }
  fs.cpSync(path.join(ROOT, 'static'), DIST, { recursive: true }); // styles, i18n, images, robots, sitemap, roadmap.md
  console.log(`built static site -> dist/ (${Object.keys(pages).length} pages + assets)`);
})();
