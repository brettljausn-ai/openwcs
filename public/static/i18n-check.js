#!/usr/bin/env node
/* CI guard: every data-i18n key used across the views (public/views/layout.ejs + views/pages/*.ejs)
 * must exist in all six language dictionaries in i18n.js. Exits non-zero if any key is missing. */
'use strict';
const fs = require('fs');
const path = require('path');

const dir = __dirname;                                   // public/static (where i18n.js lives)
const viewsDir = path.join(dir, '..', 'views');          // the source views (shared layout + page bodies)
const pagesDir = path.join(viewsDir, 'pages');
const src = fs.readFileSync(path.join(dir, 'i18n.js'), 'utf8');

const start = src.indexOf('var I18N = {');
const end = src.indexOf('\n  };', start);
if (start === -1 || end === -1) { console.error('Could not locate I18N object in i18n.js'); process.exit(2); }
const objText = src.slice(start + 'var I18N = '.length, end + '\n  }'.length);
let I18N;
eval('I18N=' + objText + ';');

const langs = ['en', 'de', 'fr', 'es', 'zh', 'pt'];
// Scan the shared layout (nav/footer/contact keys) + every page body view.
const files = [
  path.join(viewsDir, 'layout.ejs'),
  ...fs.readdirSync(pagesDir).filter(f => f.endsWith('.ejs')).map(f => path.join(pagesDir, f)),
];
const keys = new Set();
const re = /data-i18n="([^"]+)"/g;
for (const f of files) {
  const t = fs.readFileSync(f, 'utf8');
  let m;
  while ((m = re.exec(t))) keys.add(m[1]);
}

const missing = [];
for (const k of keys) {
  for (const l of langs) {
    if (!I18N[l] || I18N[l][k] == null) missing.push(l + ':' + k);
  }
}

console.log('Views scanned:        ' + files.length);
console.log('Distinct keys in views: ' + keys.size);
console.log('Dictionary sizes:      ' + langs.map(l => l + '=' + Object.keys(I18N[l] || {}).length).join(', '));
if (missing.length) {
  console.error('\nMISSING translations (' + missing.length + '):');
  for (const x of missing) console.error('  ' + x);
  process.exit(1);
}
console.log('\nOK — every data-i18n key resolves in all 6 languages.');
