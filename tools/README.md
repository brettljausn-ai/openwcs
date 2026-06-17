# tools

Maintenance scripts, not part of the application build.

## demo-screenshots.mjs

Captures clean screenshots of the live demo (logged in, with seeded data) so the public
marketing site (`public/static/shots/`) and docs show real screens instead of empty or error
states.

```bash
cd tools
npm install            # installs playwright
npx playwright install chromium
node demo-screenshots.mjs
```

Env vars (all optional, default to the public demo): `DEMO_URL`, `DEMO_USER`, `DEMO_PASS`,
`OUT_DIR`, `SHOT_WIDTH`. Output PNGs land in `../.demo-screenshots/` (gitignored), captured at
retina (2x).

To refresh the site assets, downscale and copy the ones you want:

```bash
for f in ../.demo-screenshots/*.png; do sips --resampleWidth 1600 "$f"; done
cp ../.demo-screenshots/<name>.png ../public/static/shots/
# then rebuild the static site:  cd ../public && npm run build:static
```
