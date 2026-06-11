// One-time (re-runnable) converter: turns the legacy standalone *.html pages into EJS body views +
// a pages.json manifest, so the Express app can render them through a single shared layout.
//
// For each page it extracts:
//   - headMeta  : the page's <head> minus the invariant tags (charset/viewport/fonts/styles/i18n)
//                 — i.e. the per-page SEO (title, description, canonical, OG/Twitter, JSON-LD). Kept
//                 verbatim so nothing regresses.
//   - body      : the page content. The shared header (incl. the global nav) and footer live in
//                 layout.ejs, so source pages carry only their content; any stray header/footer is
//                 defensively stripped here too. In-body scripts are pulled out.
//   - scripts   : any in-body <script> (e.g. roadmap.html's timeline renderer), re-emitted after body.
//
// Run:  npm run build:pages   (after editing a source page, re-run to regenerate the view).
const fs = require('fs');
const path = require('path');
const cheerio = require('cheerio');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src-html'); // legacy pages live here after the move
const VIEWS = path.join(ROOT, 'views', 'pages');
const DATA = path.join(ROOT, 'data');
fs.mkdirSync(VIEWS, { recursive: true });
fs.mkdirSync(DATA, { recursive: true });

// Head tags that are byte-identical on every page → rendered once in layout.ejs, stripped here.
const SHARED_HEAD = [
  'meta[charset]',
  'meta[name="viewport"]',
  'link[rel="preconnect"]',
  'link[href*="fonts.googleapis.com/css2"]',
  'link[rel="stylesheet"][href="styles.css"]',
  'script[src="i18n.js"]',
];

const files = fs.readdirSync(SRC).filter(f => f.endsWith('.html'));
const pages = {};

for (const file of files) {
  const $ = cheerio.load(fs.readFileSync(path.join(SRC, file), 'utf8'), { decodeEntities: false });

  const head = $('head').clone();
  SHARED_HEAD.forEach(sel => head.find(sel).remove());
  const headMeta = (head.html() || '').replace(/^\s*\n/gm, '').trim();

  const body = $('body').clone();
  body.find('header.nav, footer').remove();   // defensive: layout.ejs owns the header + footer
  const scripts = [];
  body.find('script').each((_, el) => scripts.push($.html(el)));
  body.find('script').remove();
  const bodyHtml = body.html().trim();

  const name = file.replace(/\.html$/, '');
  const route = name === 'index' ? '/' : '/' + file;
  fs.writeFileSync(path.join(VIEWS, name + '.ejs'), bodyHtml + '\n');

  pages[route] = {
    file,
    view: 'pages/' + name,
    bodyId: $('body').attr('id') || 'top',
    headMeta,
    scripts: scripts.join('\n').trim(),
  };
}

fs.writeFileSync(path.join(DATA, 'pages.json'), JSON.stringify(pages, null, 2) + '\n');
console.log(`converted ${Object.keys(pages).length} pages -> views/pages/*.ejs + data/pages.json`);
