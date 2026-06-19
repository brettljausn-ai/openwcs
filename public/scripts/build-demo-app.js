#!/usr/bin/env node
// Build the sandboxed demo SPA (the VITE_DEMO variant of /ui) and copy it into
// public/static/demo so the public site serves it same-origin at /demo-app/.
//
// Run from the public site deploy: `npm run build:demo-app` (from /public).
// The output (public/static/demo) is generated and gitignored, like dist.
//
// The demo is fully client-side: a synthetic read-only session plus an in-browser
// mock at the authFetch chokepoint, so nothing reaches a backend and nothing persists.
const { execSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const publicDir = path.resolve(__dirname, '..')
const uiDir = path.resolve(publicDir, '..', 'ui')
const out = path.join(publicDir, 'static', 'demo')

if (!fs.existsSync(path.join(uiDir, 'package.json'))) {
  console.error('Cannot find the ui project at', uiDir)
  console.error('This script must run inside the openWCS repo (ui and public are siblings).')
  process.exit(1)
}

const run = (cmd, cwd) => execSync(cmd, { cwd, stdio: 'inherit' })

console.log('Building the sandboxed demo bundle from', uiDir)
run('npm ci', uiDir)
run('npm run build:demo', uiDir)

console.log('Copying the bundle into', out)
fs.rmSync(out, { recursive: true, force: true })
fs.mkdirSync(out, { recursive: true })
fs.cpSync(path.join(uiDir, 'dist'), out, { recursive: true })

console.log('Demo bundle ready. The public site serves it at /demo-app/ (embedded by /live-demo).')
