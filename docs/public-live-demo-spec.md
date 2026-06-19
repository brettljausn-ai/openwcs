# Spec: Public sandboxed live demo (read-only app + interactive pick station)

Status: DRAFT for review (no code written yet)
Author: engineering
Related: public site (`/public`), the app SPA (`/ui`), GTP/picking, demo-mode seeding.

## 1. Goal

A public, self-contained demo of the real openWCS app that a prospect can open from the
marketing site with no login and no risk:

- They land on the **dashboard** and can open **every menu** and browse the real screens.
- They **cannot change anything** (read-only everywhere).
- On open, **5 totes arrive at the pick station** and the visitor can **pick them** (full pick,
  short pick, and the normal exceptions).
- **Nothing is persisted** anywhere: no database, no backend writes, no shared state. A page
  reload resets the demo to its starting point.
- It looks and reads like the **demo box** (`app.openwcs.ai`): we reuse its real layout and a
  snapshot of its data.

## 2. Is this a fully frontend feature?

Yes. It is a frontend-only feature and that is the recommended design. Three facts from the code
make it clean:

1. **One network chokepoint.** `ui/src/lib/authFetch.ts` installs a single global `window.fetch`
   interceptor through which every `/api/**` (and `/admin/**`) call passes. A demo flag lets us
   short-circuit there and answer from an in-browser mock, so **no request ever leaves the
   browser**. This is also a hard security guarantee: the demo build cannot reach or mutate any
   real service.
2. **Auth is replaceable.** `auth/AuthContext.tsx` loads a session object (`{token, username,
   name, roles}`) from `sessionStorage`. In demo mode we seed a synthetic read-only session and
   skip Keycloak entirely (`auth/Login.tsx` / `lib/keycloak.ts` are bypassed).
3. **Read-only already exists.** The recent access-control work gates every write control behind
   `useAuth().canWrite(screen)` / `writeAllowed(...)`. A demo session whose access level is READ on
   all screens makes the whole app render but disables creates/edits/deletes, exactly as required.

The interactive pick is the only part with real behaviour; it is a small in-memory state machine
inside the mock layer, not a backend.

Trade-off accepted: "see all menus with data" means we must answer the read endpoints those screens
call. We do that with a **snapshot of the demo box's data** (committed JSON fixtures) for the
headline screens, and **graceful empty responses** for the long tail (the app already renders empty
states cleanly). This keeps the demo honest (real shapes, real-looking data) without standing up a
backend.

## 3. User experience / scope

What the visitor gets (desktop shell, the same one at `app.openwcs.ai`):

- **Landing:** the Overview dashboard, populated from a demo-box snapshot (orders, inventory, SLA,
  dispatch, replenishment, ABC, automation, alerts).
- **All menus browseable, read-only:** Dashboards, Master data, Operations, Reporting, Engineering,
  Configuration, Administration. Headline screens show snapshot data; every write affordance is
  hidden/disabled. A persistent, dismissable banner reads "Live demo, sample data, nothing is
  saved."
- **Interactive pick station (the centrepiece):** 5 totes arrive at a goods-to-person pick station;
  the visitor presents and confirms each pick, can do a **full pick** or a **short pick**, and can
  trigger the normal exceptions (e.g. tote dirty / units broken). When all 5 are done, a short
  "demo complete, reset" affordance loops it.
- **Reset:** reloading the page (or a "Restart demo" button) re-arms the 5 totes and restores all
  snapshot data.

Non-goals (explicit): no sign-up/lead capture inside the demo, no multi-user state, no real
hardware twin streaming, no editing/persisting anything, no exposure of the real backend.

## 4. Pick surface decision

Two real pick UIs exist:

- **GTP station console** `ui/src/gtpops/GtpOpsScreen.tsx`: totes arrive in an induction queue
  (REQUESTED -> IN_TRANSIT -> QUEUED), the operator presents stock and confirms puts (full/short),
  the cycle closes and the next tote presents. This is the literal "totes go to the pick station"
  experience and is the most visual.
- **Handheld picking** `ui/src/picking/PickingScreen.tsx`: a flat pick-task queue, scan + confirm
  full/short. Simpler, but it does not show totes arriving at a station.

**Recommendation: GTP station console**, scoped to PICKING mode only. It matches "5 totes go to the
pick station" exactly and is the stronger demo. The handheld screen is the lighter fallback if we
want to cut scope. (This is the one open product choice in the spec, see section 11.)

The GTP console talks to a handful of endpoints we will mock:
`GET /api/gtp/workplaces`, `POST /api/gtp/workplaces/{id}/session`,
`GET /api/flow/induction/queue?workplaceId=`, `POST /api/gtp/stations/{id}/present`,
`POST /api/gtp/puts/{putId}/confirm`, `POST /api/gtp/cycles/{id}/close`, plus the session
heartbeat (a no-op in demo).

## 5. Architecture

### 5.1 Demo build of the existing SPA
A build-time flag `VITE_DEMO=true` (read via `import.meta.env.VITE_DEMO`) produces a demo variant of
`/ui`. No fork: the same components, shell, menus, and pick screens are reused. The flag toggles
three things: synthetic auth, the mock fetch layer, and the demo banner. Add `npm run build:demo`.

### 5.2 Synthetic read-only session
On boot in demo mode, `AuthContext` seeds a fixed session (`username: "demo"`, a display name, and a
role whose access level resolves to READ on every screen so all menus are visible but no writes are
allowed). `RequireAuth` passes; `/login` is never shown. No token is real; it is never sent anywhere
because the fetch layer is intercepted.

### 5.3 Mock service layer (the core)
A new `ui/src/demo/mockApi.ts` (only included when `VITE_DEMO`) registers a request router that
`authFetch` consults before any network call. Behaviour:
- **GET** requests: resolve from the committed fixtures (section 5.4). Unknown GETs return a benign
  empty payload (`[]` / `{}` / zeroed dashboard shape) so screens render an empty state, never an
  error.
- **Mutating** requests (POST/PUT/DELETE): by default a no-op success (so any stray write button is
  harmless), EXCEPT the whitelisted pick-station endpoints, which are handled by the in-memory pick
  engine (section 5.6) and mutate only local state.
- Small artificial latency (for example 150 to 400 ms) so the UI feels live (spinners, polling).
- Everything lives in module memory; a reload reconstructs it from fixtures, guaranteeing no
  persistence.

`authFetch.ts` gains a tiny hook: when `VITE_DEMO`, route through `mockApi` instead of `fetch`. This
is the only change to existing app code besides the auth seed and the banner.

### 5.4 Demo-box data snapshot (fixtures)
A capture script `tools/demo-snapshot.mjs` logs into the demo box once (read-only) and saves the GET
responses of the headline screens to `ui/src/demo/fixtures/*.json` (committed). Coverage target:
- all dashboard endpoints (`/api/orders/reports/*`, `/api/inventory/reports/dashboard`,
  `/api/slotting/replenishment/dashboard`, `/api/slotting/velocity/abc`,
  `/api/flow/reports/automation-summary`, `/api/notification/alerts*`,
  `/api/master-data/alert-thresholds`);
- master data lists (warehouses, SKUs, locations, areas, storage blocks, HU types);
- inventory (stock overview, handling units), orders (lists, pick-tasks), reporting datasets;
- GTP workplaces + a SKU card or two (for the pick station visuals).
Fixtures are warehouse-scoped to the demo warehouse so `?warehouseId=` lookups resolve. This
literally is the demo box's layout and data, frozen. Re-running the script refreshes the snapshot.

### 5.5 Read-only enforcement
Two layers, both already in the app: (a) the synthetic session resolves to READ on all screens, so
write controls are hidden; (b) the mock layer no-ops mutations. Together: the visitor cannot change
anything, even by crafting a request.

### 5.6 In-memory pick engine
`ui/src/demo/pickEngine.ts` holds the only "live" state: 5 totes and their lifecycle.
- On demo start it arms 5 totes for the pick station, each with a SKU (from fixtures), quantity, and
  a destination, and walks them REQUESTED -> IN_TRANSIT -> QUEUED on a short timer so the queue
  visibly fills.
- It answers the GTP endpoints: present returns a work cycle with put instructions; confirm-put
  records full or short; close-cycle completes the tote and presents the next; exceptions mark a
  tote dirty / units broken.
- It tracks progress (picked vs short vs remaining) so dashboards/tiles can reflect the session if we
  choose to wire that (optional polish).
- A `reset()` re-arms all 5. No timers or state survive a reload.

### 5.7 No persistence, no leakage
All demo state is in JS memory. The fetch interceptor blocks every real network call. The build ships
no secrets and no real tokens. Confirm in review that no `/api` call escapes the interceptor in demo
mode (a dev assertion can log any unmocked request).

## 6. The public subpage

Following the existing public-site pattern (`/public`, EJS + `data/pages.json` + `static/i18n.js`):
- New route `/live-demo` (or `/demo`) added to `public/data/pages.json` with full SEO `headMeta`.
- New template `public/views/pages/live-demo.ejs`: a short framing section (what this is, that it is
  read-only and resets) plus the embedded demo.
- Navigation: add a "Live demo" entry (the nav already has a live-demo button that currently links
  out to `app.openwcs.ai`; this becomes the in-site sandbox, with the outbound link kept as a
  secondary "open the full demo box" option).
- i18n: every visible string gets a `data-i18n` key in en/de/fr/es in `public/static/i18n.js`, and
  `node public/static/i18n-check.js` must pass (CI parity guard).

### 6.1 Embedding (same-origin, served by the public site)
The demo SPA is built from `ui/` (`npm run build:demo`, base `/demo-app/`) and its static output is
**committed into `public/static/demo/`** and served by the public Express app at `/demo-app/`. The
`/live-demo` page embeds it full-bleed via an `<iframe src="/demo-app/">`. Same-origin avoids
`X-Frame-Options`/CSP friction, removes any dependency on the app stack being up, and keeps the
marketing site fast and resilient.

Why committed (not gitignored build output): openwcs.ai is a Hostinger Node app whose application
root is only the `public/` folder, with no `ui/` build toolchain on the host, so it cannot build the
bundle at deploy time. Committing it is the only way it ships. Rebuild and re-commit it with
`npm run build:demo-app` (from `public/`) whenever a product change affects the demo (see the
keep-demo-updated rule). The bundle is one folder (~4 MB, replaced wholesale each build).

Alternative (if we prefer separation): host the demo build at `demo.openwcs.ai` and either iframe it
(set `frame-ancestors openwcs.ai` via CSP) or link to it full-screen. Recommendation is same-origin
under the public site.

## 7. Files to add or touch

New (demo, only compiled when `VITE_DEMO`):
- `ui/src/demo/mockApi.ts` (request router + fixtures loader)
- `ui/src/demo/pickEngine.ts` (5-tote in-memory state machine)
- `ui/src/demo/session.ts` (synthetic read-only session seed)
- `ui/src/demo/DemoBanner.tsx` ("Live demo, nothing is saved" + Restart)
- `ui/src/demo/fixtures/*.json` (demo-box snapshot)
- `tools/demo-snapshot.mjs` (capture script, run occasionally, not in CI)
- `public/views/pages/live-demo.ejs`, entry in `public/data/pages.json`, i18n keys

Touched (minimal, guarded by the flag so normal builds are unchanged):
- `ui/src/lib/authFetch.ts` (when `VITE_DEMO`, dispatch to `mockApi`)
- `ui/src/auth/AuthContext.tsx` (when `VITE_DEMO`, seed synthetic session, skip Keycloak)
- `ui/src/App.tsx` or shell (mount `DemoBanner`)
- `ui/vite.config.ts` + `ui/package.json` (`build:demo`)
- `public/server.js` (serve the demo build, if same-origin) and `public/views/layout.ejs` (nav)

## 8. Build, deploy, verify

- Build: `cd ui && npm run build:demo` -> static demo bundle; copy to `public/static/demo/` (or its
  own host). Normal `npm run build` is unaffected (flag off).
- Public site: standard `/public` deploy (Hostinger/Express); add the page + i18n; run i18n-check.
- Verify (manual):
  1. Open `/live-demo`: lands on the Overview dashboard with demo-box-like numbers, no login.
  2. Open each menu section: screens render (snapshot data or clean empty state), every create/edit/
     delete control is absent or disabled.
  3. Pick station: 5 totes fill the queue; present and confirm a **full pick**; do a **short pick**;
     trigger an **exception**; finish all 5; "demo complete".
  4. Reload: demo resets to 5 fresh totes and original data.
  5. Network tab: no request to any real `/api` host succeeds in leaving the browser.
- Automated (light): a Playwright smoke test that loads the demo, asserts the dashboard renders, and
  drives one full + one short pick. No backend needed (it is all client-side).

## 9. Risks and mitigations

- **Fixture coverage drift:** screens whose endpoints we did not snapshot show empty states. Mitigate
  by covering the headline screens and labelling the demo as sample data; the empty-state long tail is
  acceptable and still demonstrates the UI. Re-snapshot periodically.
- **App code coupling:** the three guarded touchpoints (authFetch, AuthContext, banner) must stay
  behind `VITE_DEMO` so the production app and its tests are byte-for-byte unaffected.
- **GTP mock fidelity:** the present/put/cycle contract must match what `GtpOpsScreen` expects; pin it
  to the real `gtpops/api.ts` types so the mock and the screen cannot drift.
- **Bundle size of fixtures:** keep snapshots trimmed (only the demo warehouse, capped list lengths)
  so the static demo loads fast.

## 10. Effort estimate (rough)

- Mock layer + auth seed + banner + flag/build: ~1 to 1.5 days.
- Fixture capture script + curating the snapshot: ~0.5 day.
- 5-tote pick engine wired to the GTP console (PICKING mode): ~1.5 to 2 days.
- Public subpage + embed + i18n + smoke test + docs/wiki/public copy: ~1 day.
Total ~4 to 5 days for one engineer, less if we choose the simpler handheld pick surface.

## 10a. Maintenance rule: keep the demo in sync with the product

Once built, the demo is a standing update target like the README, AS-BUILT, wiki, and public site:
**for any product change, update the demo in the same PR if the change is relevant to it.** Relevant
changes include new/renamed screens or menu sections, changed API response shapes, and any change to
the picking flow or the GTP present/put/cycle contract the pick engine depends on. Concretely: refresh
the affected fixtures (`tools/demo-snapshot.mjs` or hand-edit), keep `ui/src/demo/mockApi.ts` and its
types in step with changed endpoints, ensure new screens still render read-only, and re-run the demo
smoke test. Pure backend internals or infra that the demo never exercises do not require an update,
use judgement.

## 11. Decisions

- **Pick surface: GTP station console** (`ui/src/gtpops/GtpOpsScreen.tsx`, PICKING mode only). Chosen
  for the strongest "5 totes arrive at the pick station" experience. The in-memory pick engine
  (section 5.6) implements the GTP endpoints: workplaces, session, induction queue, present,
  confirm-put (full/short), close-cycle, and the dirty-tote / broken-units exceptions.
- Everything else follows the recommendations above.
