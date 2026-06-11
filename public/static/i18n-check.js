#!/usr/bin/env node
/* CI guard: every data-i18n key used across the source pages (public/src-html/*.html) must exist in
 * all four language dictionaries in i18n.js. Exits non-zero if any key is missing. */
'use strict';
const fs = require('fs');
const path = require('path');

const dir = __dirname;                                   // public/static (where i18n.js lives)
const pagesDir = path.join(dir, '..', 'src-html');       // the editable source pages
const src = fs.readFileSync(path.join(dir, 'i18n.js'), 'utf8');

const start = src.indexOf('var I18N = {');
const end = src.indexOf('\n  };', start);
if (start === -1 || end === -1) { console.error('Could not locate I18N object in i18n.js'); process.exit(2); }
const objText = src.slice(start + 'var I18N = '.length, end + '\n  }'.length);
let I18N;
eval('I18N=' + objText + ';');

const langs = ['en', 'de', 'fr', 'es'];
const htmls = fs.readdirSync(pagesDir).filter(f => f.endsWith('.html'));
const keys = new Set();
const re = /data-i18n="([^"]+)"/g;
for (const f of htmls) {
  const t = fs.readFileSync(path.join(pagesDir, f), 'utf8');
  let m;
  while ((m = re.exec(t))) keys.add(m[1]);
}

const missing = [];
for (const k of keys) {
  for (const l of langs) {
    if (!I18N[l] || I18N[l][k] == null) missing.push(l + ':' + k);
  }
}

console.log('Pages scanned:        ' + htmls.length);
console.log('Distinct keys in HTML: ' + keys.size);
console.log('Dictionary sizes:      ' + langs.map(l => l + '=' + Object.keys(I18N[l] || {}).length).join(', '));
if (missing.length) {
  console.error('\nMISSING translations (' + missing.length + '):');
  for (const x of missing) console.error('  ' + x);
  process.exit(1);
}
console.log('\nOK — every data-i18n key resolves in all 4 languages.');
