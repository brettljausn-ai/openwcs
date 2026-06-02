# BJ Assistant — Dark / Glassy / Futuristic Styling Guide

A copy-pasteable reference for replicating the BJ Assistant visual language in
other BrettlJausn / brand-adjacent projects. Covers the design tokens,
background system, glass primitives, typography, components, and animations
used across the public landing page (`src/views/landing.ejs`) and the app
shell (`public/css/style.css`).

The aesthetic is **dark forest with a herbal-lime glow**: a forest-abyss base
under layered radial aurora gradients, glassmorphic surfaces with backdrop
blur, JetBrains Mono accents on small labels, Cormorant Garamond italic
emphasis with herbal-lime gradient text on hero words, and pill-shaped
herbal-lime CTAs that emit a soft glow on hover.

---

## 1. Design tokens

Drop this `:root` block at the top of your stylesheet. Every component below
reads from these variables — change a token here and it cascades.

```css
:root {
  /* Brand */
  --brand-forest: #1F4D3A;        /* alpine forest — used only for solid fills (heatmap top tier, focus tag) */
  --forest-deep: #0E2820;          /* slightly lighter than abyss — used as opaque modal/dialog fill */
  --forest-abyss: #061812;         /* page background base */
  --herbal-lime: #8DC63F;          /* primary accent / CTA / glow */
  --herbal-glow: rgba(141, 198, 63, .55);
  --alpine-white: #F7F9F8;         /* button text on lime, top-tier swatches */

  /* Glass surfaces — translucent whites layered over the abyss */
  --glass-bg: rgba(255, 255, 255, .04);
  --glass-bg-strong: rgba(255, 255, 255, .07);
  --glass-border: rgba(255, 255, 255, .09);
  --glass-border-bright: rgba(141, 198, 63, .35);

  /* Text */
  --text: rgba(247, 249, 248, .92);
  --text-dim: rgba(247, 249, 248, .62);
  --text-faint: rgba(247, 249, 248, .42);

  /* Semantic */
  --primary: var(--herbal-lime);
  --bg: var(--forest-abyss);
  --card-bg: var(--glass-bg);
  --border: var(--glass-border);
  --success: var(--herbal-lime);
  --danger: #ff6b5e;     /* lightened so it reads on dark — original brand red was #c0392b */
  --warning: #f4b860;    /* lightened amber — original was #e67e22 */

  /* Typography */
  --font-heading: 'Cormorant Garamond', Georgia, serif;
  --font-body: 'DM Sans', system-ui, sans-serif;
  --font-mono: 'JetBrains Mono', ui-monospace, monospace;

  /* Geometry */
  --radius: 10px;
  --shadow: 0 4px 16px rgba(0, 0, 0, .25), 0 1px 2px rgba(0, 0, 0, .15);
  --shadow-md: 0 12px 32px rgba(0, 0, 0, .35), 0 0 24px -6px var(--herbal-glow);
}
```

**Status tint convention** — instead of solid hex pastels, status states use
translucent lime/amber/red over the abyss:

| State    | Background                      | Text       | Border                            |
|----------|----------------------------------|------------|-----------------------------------|
| Success  | `rgba(141, 198, 63, .15)`        | `#8DC63F`  | `rgba(141, 198, 63, .3)`          |
| Warning  | `rgba(244, 184, 96, .15)`        | `#f4b860`  | `rgba(244, 184, 96, .3)`          |
| Danger   | `rgba(255, 107, 94, .15)`        | `#ff8a80`  | `rgba(255, 107, 94, .3)`          |
| Info     | `rgba(141, 198, 63, .12)`        | `#8DC63F`  | `rgba(141, 198, 63, .25)`         |

---

## 2. Fonts

Single Google Fonts import — three families, all light-weight:

```html
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:ital,wght@0,300;0,400;1,300;1,400&family=DM+Sans:wght@300;400;500&family=JetBrains+Mono:wght@300;400;500&display=swap" rel="stylesheet">
```

**Usage rules:**
- `Cormorant Garamond` — all `h1-h4`, italic emphasis on hero words. Default weight 300.
- `DM Sans` — body text, button labels, form fields. Default weight 300.
- `JetBrains Mono` — eyebrow labels, step numbers (`01`/`02`/`03`), feature
  tags (`// 04 · Tasks`), stat digits, table headers, top clock, footer.

---

## 3. Background system (cosmic layers)

Two fixed layers behind every page. The first is gradient aurora, the second
is fine SVG noise. Both are `pointer-events: none` so they don't intercept
clicks.

For a single-page site, attach them to `body::before` / `body::after`. For
multi-page apps, use absolutely-positioned `<div>`s in the layout so they
work regardless of body styling.

```css
body {
  background: var(--forest-abyss);
  color: var(--text);
  position: relative;
  min-height: 100vh;
  overflow-x: hidden;
}

/* Aurora gradient — three radial blobs of forest + lime over the abyss */
body::before {
  content: '';
  position: fixed;
  inset: 0;
  z-index: -2;
  pointer-events: none;
  background:
    radial-gradient(ellipse 80% 50% at 50% 0%, rgba(141, 198, 63, .14), transparent 60%),
    radial-gradient(ellipse 60% 40% at 85% 30%, rgba(47, 107, 79, .3), transparent 60%),
    radial-gradient(ellipse 70% 60% at 10% 70%, rgba(31, 77, 58, .4), transparent 60%),
    linear-gradient(180deg, var(--forest-abyss) 0%, #081e16 50%, var(--forest-abyss) 100%);
}

/* Subtle SVG fractal noise — masked to overlay only and softened with low opacity */
body::after {
  content: '';
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
  opacity: .35;
  mix-blend-mode: overlay;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='2' stitchTiles='stitch'/%3E%3CfeColorMatrix values='0 0 0 0 .55 0 0 0 0 .78 0 0 0 0 .25 0 0 0 .35 0'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
}
```

**Pulsing hero orb** (optional, for the landing hero only):

```css
.hero-orb {
  position: absolute;
  width: 600px;
  height: 600px;
  border-radius: 50%;
  top: -100px;
  left: 50%;
  transform: translateX(-50%);
  background: radial-gradient(circle, rgba(141, 198, 63, .12) 0%, transparent 70%);
  filter: blur(40px);
  z-index: -1;
  animation: orbPulse 8s ease-in-out infinite;
}

@keyframes orbPulse {
  0%, 100% { transform: translateX(-50%) scale(1);    opacity: .6; }
  50%      { transform: translateX(-50%) scale(1.15); opacity: .9; }
}
```

> **Avoided:** A drifting grid mesh (`linear-gradient` lines on a 56px tile)
> was prototyped and removed — it read as visual clutter behind glass cards.
> If you want subtle motion, the orb pulse alone is enough.

---

## 4. Glass card primitive

The single reusable surface for cards, modals, panels, and pills. Use class
`glass` and compose with sub-classes for each component.

```css
.glass {
  position: relative;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: 20px;
  backdrop-filter: blur(20px) saturate(140%);
  -webkit-backdrop-filter: blur(20px) saturate(140%);
  transition: all .35s ease;
  overflow: hidden;
}

/* Gradient hairline border — uses CSS mask trick to draw only the 1px ring */
.glass::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  padding: 1px;
  background: linear-gradient(135deg,
    rgba(255, 255, 255, .18),
    transparent 40%,
    transparent 60%,
    rgba(141, 198, 63, .12));
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
          mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor;
          mask-composite: exclude;
  pointer-events: none;
}

.glass:hover {
  background: var(--glass-bg-strong);
  border-color: var(--glass-border-bright);
  transform: translateY(-2px);
}
```

**Gotcha:** The `::before` mask trick draws the gradient border without
needing `overflow: hidden`. So if a child element needs to escape the card
(e.g. a "MOST POPULAR" badge that sits half above the top edge), override
`overflow: visible` on that specific composed class — the gradient border
still renders correctly.

---

## 5. Buttons

Pill-shaped, three variants. Primary emits a glow halo that intensifies on
hover.

```css
.btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: .5rem;
  padding: .75rem 1.5rem;
  border-radius: 999px;
  font-family: var(--font-body);
  font-size: .9375rem;
  font-weight: 400;
  text-decoration: none;
  transition: all .25s ease;
  border: 1px solid transparent;
  cursor: pointer;
  isolation: isolate;
}

.btn-primary {
  background: var(--herbal-lime);
  color: var(--forest-abyss) !important;
  font-weight: 500;
  box-shadow: 0 0 0 1px rgba(141, 198, 63, .3),
              0 8px 32px -8px var(--herbal-glow);
}
.btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 0 0 1px rgba(141, 198, 63, .5),
              0 12px 40px -8px var(--herbal-glow),
              0 0 24px var(--herbal-glow);
}

.btn-ghost {
  background: var(--glass-bg);
  color: var(--text) !important;
  border: 1px solid var(--glass-border);
  backdrop-filter: blur(8px);
}
.btn-ghost:hover {
  background: var(--glass-bg-strong);
  border-color: var(--glass-border-bright);
}

.btn-outline {
  background: transparent;
  color: var(--text) !important;
  border: 1px solid rgba(141, 198, 63, .4);
}
.btn-outline:hover {
  border-color: var(--herbal-lime);
  box-shadow: 0 0 24px -4px var(--herbal-glow);
}

.btn-lg { padding: 1rem 2rem; font-size: 1rem; }

/* Animated arrow that nudges right on hover */
.btn .arrow { display: inline-block; transition: transform .2s; }
.btn:hover .arrow { transform: translateX(3px); }
```

```html
<a href="/signup" class="btn btn-primary btn-lg">Get started <span class="arrow">→</span></a>
<a href="/login"  class="btn btn-ghost">Sign in</a>
```

---

## 6. Form controls

Translucent forest fill, `--text` colour, lime focus halo. The select-dropdown
caret is replaced with a lime-tinted SVG (the default would be invisible on
dark).

```css
.form-control {
  width: 100%;
  padding: .625rem .875rem;
  border: 1px solid var(--glass-border);
  border-radius: var(--radius);
  font-family: var(--font-body);
  font-size: .9375rem;
  font-weight: 300;
  color: var(--text);
  background: rgba(8, 30, 22, .35);
  backdrop-filter: blur(8px);
  transition: border-color .15s, box-shadow .15s, background .15s;
}

.form-control::placeholder { color: var(--text-faint); }

.form-control:focus {
  outline: none;
  border-color: var(--herbal-lime);
  background: rgba(8, 30, 22, .55);
  box-shadow: 0 0 0 3px rgba(141, 198, 63, .18),
              0 0 16px -4px var(--herbal-glow);
}

select.form-control {
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 12 12'%3E%3Cpath fill='%238DC63F' d='M6 8L1 3h10z'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right .75rem center;
  padding-right: 2rem;
}

/* Native option dropdown is unstyled by browsers — at least set bg/color so
   the dropdown panel reads on dark systems. */
select.form-control option {
  background: var(--forest-deep);
  color: var(--text);
}
```

---

## 7. Toggle switch

Dark pin on a translucent track when off, dark pin on lime track with glow
when on.

```css
.toggle-switch { position: relative; width: 48px; height: 26px; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }

.toggle-slider {
  position: absolute;
  inset: 0;
  cursor: pointer;
  background: rgba(255, 255, 255, .12);
  border: 1px solid var(--glass-border);
  border-radius: 26px;
  transition: .3s;
}
.toggle-slider::before {
  content: '';
  position: absolute;
  height: 18px; width: 18px;
  left: 3px; bottom: 3px;
  background: var(--alpine-white);
  border-radius: 50%;
  transition: .3s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .3);
}

.toggle-switch input:checked + .toggle-slider {
  background: var(--herbal-lime);
  border-color: var(--herbal-lime);
  box-shadow: 0 0 16px -2px var(--herbal-glow);
}
.toggle-switch input:checked + .toggle-slider::before {
  background: var(--forest-abyss);
  transform: translateX(24px);
}
```

---

## 8. Tables

Mono uppercase headers in lime, hover row gets a subtle lime wash.

```css
th {
  font-family: var(--font-mono);
  font-size: .7rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: .12em;
  color: var(--herbal-lime);
  background: var(--glass-bg-strong);
  border-bottom: 1px solid var(--glass-border-bright);
}

th, td {
  padding: .75rem 1rem;
  text-align: left;
  border-bottom: 1px solid var(--glass-border);
}

tr:hover { background: rgba(141, 198, 63, .04); }
```

---

## 9. Modal / dialog

Two flavours:
1. **Branded modal** — for short interactions (confirm, alert, prompt).
   Translucent so it reads as a "popover" over the page.
2. **Opaque dialog** — for long forms (compose email, new meeting). Solid
   `--forest-deep` so dense content reads cleanly.

Both share the glowing top-edge accent.

```css
/* Backdrop — covers the page, blurs anything behind it */
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(6, 24, 18, .65);
  backdrop-filter: blur(8px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  z-index: 1000;
}

/* Branded modal — translucent glass */
.modal {
  background: rgba(14, 40, 32, .85);
  backdrop-filter: blur(24px) saturate(140%);
  border: 1px solid var(--glass-border);
  border-radius: 16px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, .6),
              0 0 40px -10px var(--herbal-glow);
  padding: 2rem;
  position: relative;
}

/* Opaque dialog — for forms with many inputs */
.dialog {
  background: var(--forest-deep);
  border: 1px solid var(--glass-border);
  border-radius: 14px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, .6),
              0 0 40px -10px var(--herbal-glow);
  position: relative;
}

/* Glowing top-edge accent — shared by both */
.modal::before,
.dialog::before {
  content: '';
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 50%;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--herbal-lime), transparent);
  box-shadow: 0 0 12px var(--herbal-glow);
}
```

> **Rule of thumb:** if the dialog has more than two form fields, use the
> opaque variant. Translucent dialogs over glass cards look beautiful but
> make dense content hard to read.

> **Width caps:** the default `.dialog` caps at `max-width: 540px`. For
> dialogs with side-by-side content (scheduling assistant slots, suggested
> times, attendee chips), bump to `max-width: min(880px, calc(100vw - 2rem))`
> on the specific composed class so it still respects narrow viewports.

---

## 10. Sidebar (app shell)

Frosted backdrop with subtle herbal-lime accents on active nav items.

```css
.sidebar {
  width: 260px;
  position: fixed;
  inset: 0 auto 0 0;
  padding: 1.5rem;
  background: rgba(8, 30, 22, .55);
  backdrop-filter: blur(20px) saturate(140%);
  border-right: 1px solid var(--glass-border);
  color: var(--alpine-white);
  display: flex;
  flex-direction: column;
}

.sidebar-nav a {
  display: flex;
  align-items: center;
  gap: .75rem;
  padding: .75rem 1rem;
  color: var(--text-dim);
  text-decoration: none;
  border-radius: 8px;
  transition: all .15s;
}

.sidebar-nav a:hover,
.sidebar-nav a.active {
  background: rgba(141, 198, 63, .12);
  color: var(--herbal-lime);
}

/* Optional brand stripe at the very top of the page (sits above sidebar) */
.top-bar {
  position: fixed;
  top: 0;
  left: 260px;
  right: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--herbal-lime), transparent);
  opacity: .35;
  z-index: 99;
}
```

---

## 11. Top-right clock pill

Glass pill with mono digits. Time is in `--herbal-lime`, date is faint.

```css
.top-clock {
  position: fixed;
  top: .75rem;
  right: 1.25rem;
  z-index: 200;
  display: flex;
  align-items: center;
  gap: .5rem;
  padding: 0 .75rem;
  height: 2.375rem;
  background: rgba(8, 30, 22, .55);
  border: 1px solid var(--glass-border);
  border-radius: var(--radius);
  font-family: var(--font-mono);
  color: var(--text);
  backdrop-filter: blur(16px) saturate(140%);
}
.top-clock-date { font-size: .75rem; color: var(--text-faint); letter-spacing: .04em; }
.top-clock-time { font-size: .9375rem; color: var(--herbal-lime); font-variant-numeric: tabular-nums; letter-spacing: .04em; }
```

---

## 12. Typography accents

### Eyebrow label

A short uppercase mono caption above section headings, with a pulsing dot.

```css
.eyebrow {
  font-family: var(--font-mono);
  font-size: .75rem;
  letter-spacing: .18em;
  text-transform: uppercase;
  color: var(--herbal-lime);
  display: inline-flex;
  align-items: center;
  gap: .5rem;
}

.eyebrow::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--herbal-lime);
  box-shadow: 0 0 10px var(--herbal-glow);
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1;   transform: scale(1); }
  50%      { opacity: .55; transform: scale(.85); }
}
```

```html
<div class="eyebrow">AI · Microsoft 365 · WhatsApp</div>
```

### Italic gradient emphasis

Hero headlines are `Cormorant Garamond` italic with a herbal-lime gradient
text fill on emphasised words.

```css
.hero h1 em {
  font-style: italic;
  background: linear-gradient(135deg, var(--herbal-lime) 0%, #c5e890 50%, #A8C7B5 100%);
  -webkit-background-clip: text;
          background-clip: text;
  -webkit-text-fill-color: transparent;
}
```

```html
<h1>Your inbox, <em>handled</em>.<br>Your time, <em>reclaimed</em>.</h1>
```

### Mono category tag

For feature cards: `// 04 · Tasks` style category prefix above a card heading.

```css
.feature-tag {
  font-family: var(--font-mono);
  font-size: .65rem;
  letter-spacing: .15em;
  text-transform: uppercase;
  color: var(--herbal-lime);
  margin-bottom: 1.25rem;
  opacity: .85;
}
```

### Terminal-style panel header

Optional traffic-light dots for a "code window" feel — used on the privacy
panel of the landing.

```html
<div class="privacy-header">
  <span class="privacy-dots"><span></span><span></span><span></span></span>
  <span>// data_handling.md</span>
</div>
```

```css
.privacy-dots { display: inline-flex; gap: 6px; }
.privacy-dots span {
  width: 10px; height: 10px;
  border-radius: 50%;
  background: var(--glass-border-bright);
  opacity: .5;
}
.privacy-dots span:first-child { background: var(--herbal-lime); opacity: .7; }
```

---

## 13. Animations

### Reveal on scroll

Elements fade up as they enter the viewport. Pure IntersectionObserver, no
library.

```css
.reveal {
  opacity: 0;
  transform: translateY(24px);
  transition: opacity .8s ease, transform .8s ease;
}
.reveal.is-visible {
  opacity: 1;
  transform: translateY(0);
}
```

```javascript
(function() {
  var els = document.querySelectorAll('.reveal');
  if (!('IntersectionObserver' in window)) {
    els.forEach(function(el) { el.classList.add('is-visible'); });
    return;
  }
  var io = new IntersectionObserver(function(entries) {
    entries.forEach(function(entry) {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        io.unobserve(entry.target);
      }
    });
  }, { threshold: 0.12, rootMargin: '0px 0px -40px 0px' });
  els.forEach(function(el) { io.observe(el); });
})();
```

### Counter animation

Stat numbers count up from 0 when scrolled into view. Cubic-ease with
locale-aware formatting.

```html
<div class="stat-number" data-counter="12345">0</div>
```

```javascript
(function() {
  var counters = document.querySelectorAll('[data-counter]');
  if (!counters.length) return;

  function animate(el) {
    var target = parseInt(el.getAttribute('data-counter'), 10) || 0;
    if (target === 0) { el.textContent = '0'; return; }
    var duration = 1400;
    var start = performance.now();
    function tick(now) {
      var t = Math.min(1, (now - start) / duration);
      var eased = 1 - Math.pow(1 - t, 3);
      el.textContent = Math.floor(target * eased).toLocaleString();
      if (t < 1) requestAnimationFrame(tick);
      else el.textContent = target.toLocaleString();
    }
    requestAnimationFrame(tick);
  }

  if (!('IntersectionObserver' in window)) {
    counters.forEach(animate);
    return;
  }
  var io = new IntersectionObserver(function(entries) {
    entries.forEach(function(entry) {
      if (entry.isIntersecting) {
        animate(entry.target);
        io.unobserve(entry.target);
      }
    });
  }, { threshold: 0.4 });
  counters.forEach(function(el) { io.observe(el); });
})();
```

### Reduced-motion support

Always include this — disables decorative animations for users who prefer
reduced motion.

```css
@media (prefers-reduced-motion: reduce) {
  .reveal { opacity: 1; transform: none; transition: none; }
  .hero-orb,
  .eyebrow::before,
  .live-dot { animation: none; }
}
```

---

## 14. Component anatomy

### Pricing card with featured badge

The `MOST POPULAR` badge sits half above the card via `translateY(-50%)`.
The shared `.glass` primitive applies `overflow: hidden`, so override
`overflow: visible` on `.pricing-card` (the gradient border uses a CSS mask,
not clipping, so it still renders).

```html
<article class="glass pricing-card pricing-card-featured">
  <div class="pricing-badge">Most popular</div>
  <h3>Mid Tier</h3>
  <div class="price">
    <span class="amount">€30</span>
    <span class="per">per user · month</span>
  </div>
  <ul>
    <li>Up to 50 users</li>
    <li>Priority support</li>
  </ul>
  <a href="/signup" class="btn btn-primary btn-block">Sign up <span class="arrow">→</span></a>
</article>
```

```css
.pricing-card {
  padding: 2.5rem 2rem;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: visible;        /* let the badge poke above the top edge */
}

.pricing-card-featured {
  background: linear-gradient(180deg,
    rgba(141, 198, 63, .08),
    rgba(141, 198, 63, .02));
  border-color: var(--glass-border-bright);
  box-shadow: 0 0 60px -20px var(--herbal-glow);
}

.pricing-badge {
  position: absolute;
  top: -1px;
  left: 50%;
  transform: translateX(-50%) translateY(-50%);
  background: var(--herbal-lime);
  color: var(--forest-abyss);
  padding: .375rem .875rem;
  border-radius: 999px;
  font-family: var(--font-mono);
  font-size: .65rem;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: .12em;
  box-shadow: 0 4px 24px -4px var(--herbal-glow);
}

.pricing-card ul li::before {
  content: '';
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--herbal-lime);
  box-shadow: 0 0 6px var(--herbal-glow);
}
```

### "How it works" with glowing connector

Three glass cards in a row, joined by a horizontal lime hairline that
threads through the step-number circles.

```css
.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1.5rem;
  position: relative;
}

.steps::before {
  content: '';
  position: absolute;
  top: 38px;        /* matches the centre of the step-number circles */
  left: 16%;
  right: 16%;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--herbal-lime), transparent);
  opacity: .35;
  z-index: 0;
}

.step-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--forest-abyss);
  border: 1px solid var(--glass-border-bright);
  color: var(--herbal-lime);
  font-family: var(--font-mono);
  font-size: .8125rem;
  box-shadow: 0 0 24px -4px var(--herbal-glow),
              inset 0 0 12px rgba(141, 198, 63, .08);
}
```

### Insight card with sectioned bullets

A glass card whose body is a short summary paragraph followed by 1–N
sections, each with its own icon + title + "Open" deep-link, then either a
single `<p>` or a `<ul>` of bullets. Used for the dashboard's AI daily
brief, but the pattern is reusable for any "AI-summarised view of multiple
data sources" surface.

Sections are separated by hairlines (`border-top` on each section,
suppressed on `:first-of-type`). Bullet text is split on the literal
character `•` so AI prompts can return flat single-string fields and the UI
turns them into a list automatically.

```html
<div class="card daily-brief-card" id="daily-brief">
  <div class="card-header">
    <h2>Today's Brief</h2>
    <button type="button" class="btn btn-outline btn-sm" id="daily-brief-refresh">Refresh</button>
  </div>
  <div class="daily-brief-body">
    <p class="daily-brief-summary">One sentence framing the day overall.</p>

    <section class="daily-brief-section">
      <header>
        <span class="daily-brief-icon" aria-hidden="true">✉</span>
        <h3>Emails to focus on</h3>
        <a class="daily-brief-link" href="/inbox">Open</a>
      </header>
      <ul class="daily-brief-bullets">
        <li>Reply to Anna about the NDA.</li>
        <li>Confirm Friday's call with Bob.</li>
      </ul>
    </section>

    <section class="daily-brief-section">…</section>
  </div>
</div>
```

```css
.daily-brief-card { margin-bottom: 1.5rem; }

.daily-brief-card .card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: .75rem;
}

.daily-brief-summary {
  font-family: var(--font-heading);
  font-weight: 300;
  font-size: 1.0625rem;
  line-height: 1.5;
  color: var(--alpine-forest);
  margin: 0 0 1rem;
}

.daily-brief-section {
  border-top: 1px solid var(--border);
  padding: .875rem 0 .25rem;
}
.daily-brief-section:first-of-type { border-top: 0; }

.daily-brief-section header {
  display: flex;
  align-items: center;
  gap: .5rem;
  margin-bottom: .375rem;
}

.daily-brief-icon { font-size: 1rem; line-height: 1; }

.daily-brief-section h3 {
  font-size: .9375rem;
  margin: 0;
  flex: 1 1 auto;
  color: var(--alpine-forest);
}

.daily-brief-link {
  font-size: .8125rem;
  color: var(--forest-mid);
  text-decoration: none;
}
.daily-brief-link:hover { color: var(--alpine-forest); text-decoration: underline; }

.daily-brief-bullets {
  margin: 0;
  padding-left: 1.1rem;
  display: flex;
  flex-direction: column;
  gap: .25rem;
}

.daily-brief-bullets li,
.daily-brief-section p {
  font-size: .9375rem;
  line-height: 1.5;
  color: var(--alpine-forest);
  margin: 0;
}
```

The mobile twin lives in `public/css/mobile.css` under `.bj-daily-brief-*`
with smaller type and tighter padding — same structure, mobile-tuned
spacing.

### Detail-pane header with action toolbar

For two-pane reading layouts (inbox detail, message reader), the right pane
gets a header with three stacked rows:

1. **Top row** — priority/status badges on the left, action buttons on the
   right (Send / Reply / Forward / Archive / Delete). `flex-wrap: wrap` so
   buttons drop to a second line on narrow viewports instead of compressing
   into a column beside the heading.
2. **Subject** — full-width `h2` so long subjects break naturally
   (`word-break: break-word`).
3. **Meta row** — `From / To / Cc / Date` as a single flex-wrapped line, with
   the date pushed to the right (`margin-left: auto`).

This avoids the failure mode where a tall button column on the side squeezes
the subject into a narrow column.

```html
<div class="inbox-view-head">
  <div class="inbox-view-head-top">
    <div class="inbox-view-head-badges">
      <span class="badge"></span>
    </div>
    <div class="inbox-head-actions">
      <button class="btn btn-accent btn-sm">Send Reply</button>
      <button class="btn btn-outline btn-sm">Reply All</button>
      <button class="btn btn-outline btn-sm">Forward</button>
      <button class="btn btn-outline btn-sm">Archive</button>
      <button class="btn btn-danger btn-sm">Delete</button>
    </div>
  </div>
  <h2>RE:FW: NDA Brettljausn (former Ethon Advisory)</h2>
  <p class="inbox-view-meta">
    <span class="inbox-view-meta-row">From <strong>Gerets, Denise</strong></span>
    <span class="inbox-view-meta-row">To <span>karl@brettljausn.ai</span></span>
    <span class="inbox-view-meta-row">Cc <span>legal@brettljausn.ai</span></span>
    <span class="inbox-view-meta-row inbox-view-meta-date">29 Apr 2026, 14:21</span>
  </p>
</div>
```

```css
.inbox-view-head {
  display: flex;
  flex-direction: column;
  gap: .5rem;
  padding-bottom: 1rem;
  border-bottom: 1px solid var(--border);
  margin-bottom: 1rem;
}

.inbox-view-head-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 1rem;
  flex-wrap: wrap;
}

.inbox-view-head-badges { display: flex; gap: .5rem; align-items: center; flex-wrap: wrap; }

.inbox-head-actions {
  display: flex;
  gap: .375rem;
  flex-wrap: wrap;
  justify-content: flex-end;
  max-width: 100%;
}

.inbox-view-head h2 {
  font-size: 1.5rem;
  margin: 0;
  word-break: break-word;
}

.inbox-view-meta {
  font-size: .875rem;
  color: var(--forest-mid);
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: .25rem 1rem;
}

.inbox-view-meta-row { display: inline-flex; gap: .35rem; align-items: baseline; min-width: 0; }
.inbox-view-meta-row strong,
.inbox-view-meta-row span { overflow-wrap: anywhere; }
.inbox-view-meta-date { margin-left: auto; }

@media (max-width: 900px) {
  .inbox-view-meta-date { margin-left: 0; }
  .inbox-head-actions { justify-content: flex-start; }
}
```

### Live stats pill

A small glass pill under the hero CTAs showing a real-time count.

```html
<div class="hero-meta">
  <span class="live-dot"></span>
  <span>LIVE</span>
  <span style="opacity:.5">·</span>
  <span><span data-counter="12345">0</span> emails processed to date</span>
</div>
```

```css
.hero-meta {
  display: inline-flex;
  align-items: center;
  gap: 1rem;
  padding: .5rem 1rem .5rem .75rem;
  background: var(--glass-bg);
  border: 1px solid var(--glass-border);
  border-radius: 999px;
  backdrop-filter: blur(8px);
  font-family: var(--font-mono);
  font-size: .75rem;
  color: var(--text-dim);
}

.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--herbal-lime);
  box-shadow: 0 0 8px var(--herbal-glow);
  animation: pulse 1.6s ease-in-out infinite;
}
```

### Flight arc visualisation

A boarding-pass-inspired hero for the calendar event modal when
`travel.mode === 'flight'`. A dashed lime arc spans the modal width with
takeoff and landing plane icons at the endpoints, the flight number floats
in a lime pill at the apex, and a three-column row underneath shows
departure ↔ arrival with IATA codes, times, timezones, and terminal
assignments — the layout reads like a paper boarding pass.

```html
<div class="cal-flight-viz">
  <div class="cal-flight-arc">
    <svg class="cal-flight-arc-svg" viewBox="0 0 100 30" preserveAspectRatio="none">
      <path d="M 4 26 Q 50 -8 96 26"/>
    </svg>
    <div class="cal-flight-apex">
      <span class="cal-flight-number">LH 1234</span>
      <a class="cal-flight-pnr cal-flight-pnr-link" href="…">
        <span class="cal-flight-pnr-label">PNR</span>
        <code>ABC123</code>
      </a>
    </div>
  </div>
  <div class="cal-flight-route">
    <div class="cal-flight-endpoint cal-flight-endpoint-depart">
      <div class="cal-flight-icon-wrap"><svg class="cal-flight-icon">…</svg></div>
      <div class="cal-flight-time">07:45</div>
      <div class="cal-flight-tz">CET</div>
      <div class="cal-flight-place">
        <span class="cal-flight-iata">VIE</span>
        <span class="cal-flight-terminal">T3</span>
      </div>
      <div class="cal-flight-place-sub">Vienna Intl.</div>
    </div>
    <div class="cal-flight-spine"></div>
    <div class="cal-flight-endpoint cal-flight-endpoint-arrive">…</div>
  </div>
</div>
```

```css
.cal-flight-viz {
  position: relative;
  margin: .25rem 0 1rem;
  padding: 1.5rem 0 .25rem;
  background: radial-gradient(120% 80% at 50% 100%, rgba(141, 198, 63, .07), transparent 60%);
  border-radius: 12px;
}
.cal-flight-arc { position: relative; height: 70px; margin: 0 36px; }
.cal-flight-arc-svg path {
  fill: none;
  stroke: var(--herbal-lime);
  stroke-width: 2;
  stroke-linecap: round;
  stroke-dasharray: 3 6;
  filter: drop-shadow(0 1px 2px rgba(141, 198, 63, .35));
}
.cal-flight-apex {
  position: absolute;
  left: 50%; top: -2px;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: .25rem;
}
.cal-flight-number {
  font-family: var(--font-mono);
  font-size: .8125rem;
  font-weight: 600;
  letter-spacing: .06em;
  color: var(--forest-abyss);
  background: var(--herbal-lime);
  padding: .2rem .65rem;
  border-radius: 999px;
  box-shadow: 0 2px 6px rgba(0, 0, 0, .25);
}
.cal-flight-route {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: start;
  gap: .5rem;
  padding: 0 1rem;
}
.cal-flight-endpoint-depart { align-items: flex-start; text-align: left; }
.cal-flight-endpoint-arrive { align-items: flex-end;   text-align: right; }
.cal-flight-icon-wrap {
  width: 40px; height: 40px;
  border-radius: 50%;
  background: rgba(141, 198, 63, .18);
  border: 1px solid rgba(141, 198, 63, .45);
  color: var(--herbal-lime);
  display: inline-flex; align-items: center; justify-content: center;
}
.cal-flight-icon-arrive { transform: scaleX(-1); }   /* mirror for landing */
.cal-flight-time {
  font-family: var(--font-heading);
  font-weight: 300;
  font-size: 1.5rem;
  line-height: 1.1;
}
.cal-flight-iata {
  font-family: var(--font-heading);
  font-weight: 300;
  font-size: 1.75rem;
  letter-spacing: .04em;
}
.cal-flight-terminal {
  font-family: var(--font-mono);
  font-size: .6875rem;
  font-weight: 600;
  background: rgba(141, 198, 63, .22);
  border: 1px solid rgba(141, 198, 63, .45);
  padding: .1rem .35rem;
  border-radius: 4px;
}
/* Tentative terminal — airline hasn't published the assignment yet */
.cal-flight-terminal.is-tentative {
  background: rgba(244, 130, 90, .14);
  border: 1px dashed rgba(244, 130, 90, .55);
  color: #f4a385;
}
.cal-flight-terminal.is-tentative::before { content: '~'; opacity: .7; }
.cal-flight-spine {
  width: 1px;
  align-self: stretch;
  background: linear-gradient(to bottom, rgba(141, 198, 63, .35), transparent);
  margin-top: .35rem;
}

@media (max-width: 600px) {
  .cal-flight-arc { margin: 0 24px; height: 56px; }
  .cal-flight-time { font-size: 1.25rem; }
  .cal-flight-iata { font-size: 1.5rem; }
}
```

### Travel calendar tile

Calendar event tiles for travel blocks (flight / train / car) get a
diagonal-stripe accent on the right edge plus a per-mode left-border tint
so they're instantly recognisable on the grid even at small sizes.

| Mode  | Left border | Stripe / fill tint           |
|-------|-------------|------------------------------|
| Flight| `#5e9ed6`   | `rgba(94, 158, 214, .18)`    |
| Train | `--herbal-lime` | `rgba(141, 198, 63, .14)` |
| Car   | `#f4825a`   | `rgba(244, 130, 90, .12)`    |

```css
.cal-mx-event.is-travel {
  position: relative;
  background: linear-gradient(135deg, rgba(244, 130, 90, .18), rgba(31, 77, 58, .85) 65%);
  border: 1px solid rgba(244, 130, 90, .35);
  border-left: 3px solid #f4825a;
  box-shadow: 0 1px 4px rgba(244, 130, 90, .18), 0 1px 3px rgba(0, 0, 0, .3);
}

/* Diagonal-stripe wedge on the right side */
.cal-mx-event.is-travel::before {
  content: '';
  position: absolute;
  top: 0; right: 0;
  width: 28px; height: 100%;
  background: repeating-linear-gradient(
    135deg,
    rgba(244, 130, 90, .12) 0 4px,
    transparent 4px 8px
  );
  border-radius: 0 4px 4px 0;
  pointer-events: none;
}

.cal-mx-event.cal-mx-travel-flight {
  background: linear-gradient(135deg, rgba(94, 158, 214, .22), rgba(31, 77, 58, .85) 65%);
  border-color: rgba(94, 158, 214, .35);
  border-left-color: #5e9ed6;
}
.cal-mx-event.cal-mx-travel-flight::before {
  background: repeating-linear-gradient(
    135deg, rgba(94, 158, 214, .18) 0 4px, transparent 4px 8px
  );
}

.cal-mx-event.cal-mx-travel-train {
  background: linear-gradient(135deg, rgba(141, 198, 63, .22), rgba(31, 77, 58, .85) 65%);
  border-color: rgba(141, 198, 63, .3);
  border-left-color: var(--herbal-lime);
}
```

The matching meeting-detail modal also tints its top edge to flag travel
mode (`.cal-event-modal.is-travel { border-top: 3px solid #f4825a; }`),
and the modal heading carries inline pills (`.cal-event-travel-pill`,
`.cal-event-travel-pill-flight` etc.) following the same per-mode palette.

### Drag-and-drop chip group

For email recipients (To / Cc / Bcc) and any other reorderable chip list.
Three signals make the drop zone discoverable:

1. The dragged chip dims to `opacity: .4` (`.compose-chip-dragging`).
2. A global `body.bj-chip-dragging` class outlines every chips container
   in dashed lime so empty containers still reveal themselves as drop
   targets.
3. The currently-hovered container goes solid lime
   (`.compose-chips-dragover`).

```css
.compose-chip[draggable="true"] { cursor: grab; }
.compose-chip[draggable="true"]:active { cursor: grabbing; }
.compose-chip-dragging { opacity: .4; }

body.bj-chip-dragging .compose-chips {
  min-height: 32px;
  outline: 1px dashed rgba(141, 198, 63, .35);
  outline-offset: 2px;
  border-radius: 6px;
}
.compose-chips-dragover {
  background: rgba(141, 198, 63, .12);
  outline: 2px dashed var(--herbal-lime) !important;
}
```

The JS that sets/removes `body.bj-chip-dragging` and
`.compose-chips-dragover` lives in `public/js/email-chips.js`.

### Scheduling assistant slot grid

Auto-fit grid of time-slot cards with a day eyebrow, large heading time,
and a range subtext. Hover lifts; `.is-picked` adds a lime tint and a
lime-glow ring. Used inside the new-meeting modal once the AI has
proposed times, and inside the WhatsApp-style "Reply with meeting" modal.

```html
<div class="cal-sched">
  <div class="cal-sched-head">
    <div class="cal-sched-title">Suggested times</div>
    <div class="cal-sched-status is-loading">Finding slots…</div>
  </div>
  <p class="cal-sched-help">Pick one — we'll send the invite.</p>
  <div class="cal-sched-slots">
    <button class="cal-sched-slot">
      <span class="cal-sched-slot-day">Wed</span>
      <span class="cal-sched-slot-time">09:30</span>
      <span class="cal-sched-slot-range">09:30–10:00</span>
    </button>
    <button class="cal-sched-slot is-picked">…</button>
  </div>
</div>
```

```css
.cal-sched {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: .75rem .9rem;
  background: rgba(255, 255, 255, .03);
}
.cal-sched-status.is-loading { color: var(--herbal-lime); }
.cal-sched-status.is-error   { color: var(--danger); }

.cal-sched-slots {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: .5rem;
}
.cal-sched-slot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: .15rem;
  padding: .65rem .5rem .7rem;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--glass-bg);
  cursor: pointer;
  transition: background .12s, border-color .12s, transform .12s, box-shadow .12s;
}
.cal-sched-slot:hover {
  background: var(--glass-bg-strong);
  border-color: rgba(141, 198, 63, .55);
  transform: translateY(-1px);
  box-shadow: 0 4px 12px -6px rgba(0, 0, 0, .35);
}
.cal-sched-slot.is-picked {
  background: rgba(141, 198, 63, .22);
  border-color: var(--herbal-lime);
  box-shadow: 0 0 0 2px rgba(141, 198, 63, .35);
}
.cal-sched-slot-day {
  font-size: .7rem;
  text-transform: uppercase;
  letter-spacing: .05em;
  color: var(--text-dim);
}
.cal-sched-slot-time {
  font-family: var(--font-heading);
  font-weight: 300;
  font-size: 1.35rem;
  line-height: 1.1;
}
```

### Off-hour calendar rows

The calendar grid keeps rows outside the user's working hours, but
shrinks them and dims them to `.75` opacity so the in-hours window
dominates. `.is-today` and hover give them a subtle lime wash so the
"now" indicator still finds them.

```css
.cal-mx-time.is-off-hour,
.cal-mx-cell.is-off-hour {
  opacity: .75;
  background: rgba(255, 255, 255, .015);
}
.cal-mx-cell.is-off-hour.is-today { background: rgba(141, 198, 63, .04); }
.cal-mx-cell.is-off-hour:hover    { background: rgba(141, 198, 63, .08); }
```

---

## 15. Mobile & PWA primitives

`public/css/mobile.css` is loaded on top of `style.css` for the mobile
layout (the `bj-mobile` device class is set by middleware before render).
Two primitives in there are reusable in any mobile web app, not just BJ.

### Loading overlay

Shown on slow SSR navigations and PWA cold starts so the user gets
immediate feedback on tap instead of a frozen screen. Built from a
backdrop + a card with a CSS-only spinner — no JS frameworks.

```html
<div class="bj-loading is-visible">
  <div class="bj-loading-card">
    <div class="bj-loading-spinner"></div>
    <span>Loading…</span>
  </div>
</div>
```

```css
.bj-loading {
  position: fixed;
  inset: 0;
  background: rgba(31, 77, 58, .18);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 90;
  opacity: 0;
  transition: opacity .15s;
  pointer-events: none;
}
.bj-loading.is-visible { opacity: 1; }

.bj-loading-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 22px;
  background: var(--alpine-white);
  color: var(--forest-abyss);
  border-radius: var(--radius);
  box-shadow: var(--shadow);
  font: 500 14px var(--font-body);
}

.bj-loading-spinner {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 2px solid rgba(31, 77, 58, .18);
  border-top-color: var(--herbal-lime);
  animation: bj-loading-spin .7s linear infinite;
}
@keyframes bj-loading-spin { to { transform: rotate(360deg); } }
```

### iOS install hint

Fixed bottom banner shown on first visit in mobile Safari to teach the
user how to add the PWA to their home screen. Sits above the bottom
nav (`--bottom-nav-h`) and the iOS home-indicator safe area, with a
slide-up entrance. The Android equivalent uses the same shell but
swaps the body copy for a real install button (handles
`beforeinstallprompt`).

```html
<div class="bj-install-hint is-visible">
  <button class="bj-install-hint-close" aria-label="Dismiss">×</button>
  <div class="bj-install-hint-title">Add to home screen</div>
  <div class="bj-install-hint-body">
    Tap <span class="bj-install-hint-share">⬆︎</span> then
    <strong>Add to Home Screen</strong> to install BJ Assistant.
  </div>
  <button class="bj-install-hint-cta">Got it</button>
</div>
```

```css
.bj-install-hint {
  position: fixed;
  left: 16px;
  right: 16px;
  bottom: calc(var(--bottom-nav-h) + 12px + env(safe-area-inset-bottom));
  background: var(--brand-forest);
  color: var(--alpine-white);
  padding: 14px 36px 14px 16px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.4;
  box-shadow: var(--shadow);
  z-index: 90;
  opacity: 0;
  transform: translateY(8px);
  transition: opacity .25s ease, transform .25s ease;
}
.bj-install-hint.is-visible { opacity: 1; transform: translateY(0); }

.bj-install-hint-title {
  font-family: var(--font-heading);
  font-weight: 300;
  font-size: 16px;
  margin-bottom: 4px;
}
.bj-install-hint-share {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  background: rgba(141, 198, 63, .25);
  vertical-align: -5px;
  margin: 0 2px;
}
.bj-install-hint-cta {
  margin-top: 10px;
  display: inline-block;
  background: var(--herbal-lime);
  color: var(--forest-abyss);
  border: 0;
  border-radius: 999px;
  padding: 10px 18px;
  font: 500 14px var(--font-body);
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(141, 198, 63, .25);
}
```

> **Tip:** `env(safe-area-inset-bottom)` is essential — without it the
> banner gets clipped behind the iOS home indicator. Same for any other
> fixed-bottom UI in standalone PWA mode.

---

## 16. Adapting an existing light-theme app

If you have a working light-theme app with `:root` tokens like
`--alpine-forest: #1F4D3A` used as both backgrounds and text, the cleanest
migration is **token redirection** — keep the token names, change their
values:

```css
:root {
  /* Brand tokens that previously named a dark colour now name a LIGHT text
     value, so the 100s of `color: var(--alpine-forest)` references read on
     dark without rewriting every component. */
  --alpine-forest: rgba(247, 249, 248, .92);   /* was #1F4D3A */
  --forest-mid:    rgba(168, 199, 181, .7);    /* was #2F6B4F */
  --soft-sand:     rgba(255, 255, 255, .04);   /* was #F2EFE7 — now glass */
  --pale-sage:     rgba(255, 255, 255, .07);   /* was #E6ECE8 — now glass-strong */

  /* Add a new token for the few selectors that still need the literal forest
     hex (sidebar accents, heatmap top tier, focus tag). */
  --brand-forest: #1F4D3A;
}
```

Then override only the handful of selectors that used `var(--alpine-forest)`
as a `background` (sidebar, primary button, brand stripe, etc.) — those need
to point at `var(--brand-forest)` or the new `var(--herbal-lime)` accent
instead.

**Audit checklist for a migration:**

- [ ] All hardcoded `#fff` / `#ffffff` card backgrounds → `var(--glass-bg-strong)`
- [ ] Light status pastels (`#fff8ed`, `#fef3f1`, `#fef2f2`, `#fffbeb`, …) → translucent equivalents (see status tint table)
- [ ] Dark text colours on light backgrounds (`#a6741f`, `#a62b1f`, `#2a7a3d`, …) → lighter equivalents (`#f4b860`, `#ff8a80`, `var(--herbal-lime)`)
- [ ] Box-shadows using forest rgba (`rgba(31,77,58,.X)`) → `rgba(0,0,0,.X)` so they read on dark
- [ ] Form `background: #fff` → `rgba(8, 30, 22, .35)` with lime focus halo
- [ ] Toggle pin colour swap (white pin off / dark pin on lime when on)
- [ ] Table `th { background: var(--soft-sand); color: var(--alpine-forest); }` → `var(--glass-bg-strong)` + lime mono
- [ ] Add a separate **opaque dialog** style for any modal with dense form content
- [ ] Add `<select>` option styling (browsers don't inherit dark theme for native dropdowns)

---

## 17. Quick file checklist

For a fresh project, these files cover the system end-to-end:

| File             | Purpose                                                          |
|------------------|------------------------------------------------------------------|
| `style.css`      | Tokens (§1) + components (§4–14) + animations (§13)              |
| `mobile.css`     | Mobile/PWA primitives (§15) — loaded on top of `style.css` when the request is mobile |
| `index.html`     | Font import (§2) + body cosmic layers (§3) + page markup         |
| `app.js`         | Reveal observer + counter animation (§13)                        |
| `email-chips.js` | Drag-and-drop chip wiring (§14 — the body class + dragover toggles) |

Total CSS for the design system itself (ignoring layout-specific rules) is
around 900 lines (≈600 desktop + ≈300 mobile/PWA). Add page-specific layout
on top.

---

## 18. Reference implementations

- **Public landing**: `src/views/landing.ejs` — full hero, stats strip,
  steps, features, privacy panel, pricing, CTA
- **App shell (desktop)**: `public/css/style.css` — sidebar, top clock,
  cards, forms, tables, badges, modal, dialog, calendar matrix, flight
  arc, scheduling assistant, todos, heatmap, drag-and-drop chips
- **App shell (mobile/PWA)**: `public/css/mobile.css` — bottom nav,
  loading overlay, iOS install hint, pull-to-refresh, bottom-sheet
  primitive, mobile twin of the daily-brief card
- **Calendar travel tiles**: `src/views/calendar.ejs` +
  `public/js/calendar-matrix.js` — flight/train/car tile classes wired
  from `travel_blocks` rows

All live in this repo — diff them against any new project to see the
system in action.
