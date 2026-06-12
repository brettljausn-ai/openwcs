Dashboard Scope Specification

This is a specification for a. the landing dashboard and b. Dashboards that sit in a main menu Dashboards section.

Landing Page:
Show current states for 
* inbound (open vs finished, in orders, handling units like received cartons, SSCC barcodes, errors today)
* Stock: What stock is holding up outbound orders? 
* Outbound: same as inbound but outbound order related
* Dispatch: what dispatch times / routes do you have, statuses and tracking to finishing
* Automation: Quick status summary and any alerts for high read error rates (>1%)

Dashboard menu
* Inbound: More content than landing page, inbound centric
* Outbound: More content than landing page, outbound centric
* Replenishment, current demand, any urgent replenishments 
* Stock: Show stock levels % to locations, compare last 90 days
* Stock: ABC Movers showing top 10 and bottom 10, raising trend etc


All dashboards are either graphs or numbers in heros, no tables

---

# Research enrichment

Deep research, June 2026: 21 sources fetched, 104 claims extracted, top 25 adversarially verified (3 independent votes each), 22 confirmed. Key primary sources: Rockwell Automation Process HMI Style Guide (ISA-101 practice), Stephen Few's Dashboard Design course (Perceptual Edge), ISA/PAS whitepaper on ANSI/ISA-18.2 alarm management, Dematic InSights brochure (the strongest direct WCS-domain evidence). Everything below marked (verified) survived a 3-vote refutation pass; design decisions without that marker are our own derivations and say so.

## 1. Architecture: two tiers, landing = situation assessment only

Industrial control-room practice (ISA-101, codified in Rockwell's HMI style guide) uses a display hierarchy whose Level 1 is a single at-a-glance overview: high-level KPIs, only the top alarm priorities, key trends, abnormal-situation indicators, readable from across the room, and with no control actions performed from it (verified). This maps exactly onto our spec:

* The landing dashboard is Level 1: heroes plus top-priority alerts only, never the full event stream. Every tile is a drill-down link into its area dashboard.
* The Dashboards menu screens are Level 2: more metrics, history, breakdowns, still read-only.
* No actions on any dashboard. Operators act on the operational screens (transport, GTP, counting); dashboards tell them where to go.

KPI-count discipline (verified, practitioner consensus): cap each area dashboard at 5 to 7 metrics; the landing page may carry up to 7 to 9 across its five tiles. Resist adding more; excess metrics are the documented failure mode of warehouse dashboards. Targets are achievable values, not stretch goals.

## 2. Hero design rules

A bare number is ineffective. Every hero must carry (verified, Few):

1. **A comparison**: target, same period last week, the 90-day norm band, or a projection ("at the current rate, done by 16:30"). Dematic's facility overview does exactly this: progress %, last-hour throughput, cycle time, plus an estimated-completion time projected from current fulfillment rates (verified).
2. **A computed state** (ok / warning / critical) driving its colour.
3. **Rounded values**. 12.4k, not 12,407. Excess precision slows operators (verified).

Presentation formats (verified, Few + Rockwell):

* Hero = big number + a horizontal bullet/limit bar (value against target with shaded ranges) + a sparkline of recent history. Bullet bars empirically beat radial gauges on speed and accuracy; no gauges anywhere.
* No bare trend arrows; they are ambiguous. A sparkline shows the same thing unambiguously.
* Multi-element panels (routes, scanners, conveyor segments, lanes) = aligned normalised bars or a heat strip, scaled so "all normal" reads as a flat line and any deviation pops without reading values.
* Current-vs-history (the stock dashboard) = current value drawn inside a shaded normal band derived from the 90-day history.
* Lists rendered as graphics (we have a no-tables rule) sort worst-first, with colour only on entries breaching threshold (verified: exception lists ranked worst-first with highlighting only on out-of-range rows).
* The most critical hero of each screen goes top-left, the most-attended screen region (verified; assumes left-to-right reading).

## 3. Colour and alert doctrine ("grey dashboard, colour = problem")

Verified across Rockwell, Few, and ISA-101 summaries:

* Neutral, muted base theme. Live data in calm, low-saturation colours. Bright intense colour is reserved exclusively for abnormal states and never used decoratively. A healthy floor looks quiet.
* One alert hue family with 2 to 3 intensity steps for urgency, not a rainbow. Never encode state by hue alone: pair colour with a shape or icon change (roughly 8% of males are colour-blind).
* This intentionally diverges from our 3D twin, which uses green/orange/red on equipment: the twin is a spatial mimic where andon colouring is conventional. Dashboards are KPI surfaces and follow the grey doctrine. Within dashboards, reuse the existing `--warning`/`--danger` tokens as the single alert family.

Alert fatigue (verified, ISA-18.2): an alarm is strictly an indication of an abnormal condition **requiring a response**. Anything an operator cannot act on must not be surfaced as an alert. Consequences for us:

* Dashboards alert only on threshold breaches (e.g. scanner no-read rate above its configured limit), never by streaming events.
* The landing automation tile shows an alert **count** by priority; the full list lives behind the drill-down.
* All thresholds in this spec are **configurable defaults**, administered in settings, not hard-coded industry truths. The 1% read-error threshold is our own default; research found vendors displaying sub-1% reject rates as heroes (Dematic shows 0.9%) but no published standard threshold.
* Later phase: an admin view of alert-system health (alerts per day, chattering alerts, stale alerts) so the alert load itself stays actionable. ISA-18.2 makes measuring the alarm system mandatory in process industries; for us it is a backlog item.

## 4. Landing page tiles

Five tiles, each: one primary hero, one or two secondary figures, a sparkline, a state colour, a drill-down. Defaults below are starting values, all configurable.

**Inbound** (drill: Inbound dashboard)
* Hero: open inbound orders (expected today, not yet finished), with projected finish time at the current receive rate.
* Secondary: HUs received today (cartons/SSCC), receive errors today (count).
* State: warning when projected finish exceeds end of receiving shift; critical when receive errors today > 5 (default).

**Stock blocking outbound** (drill: Replenishment dashboard)
* Hero: count of outbound order lines currently unfulfillable from pickable stock (allocation shortfall). This is the single most actionable stock number a control room has, so it sits top-left of the page.
* Secondary: distinct SKUs short; lines recoverable by replenishment (stock exists in reserve/ASRS) vs true zero-stock.
* State: ok at 0, warning at 1 or more, critical when more than 5% of today's open outbound lines are blocked (default).

**Outbound** (drill: Outbound dashboard)
* Hero: open outbound orders against today's released total, with projected completion time (Dematic-style estimated completion, verified pattern).
* Secondary: lines picked today, pick errors/shorts today.
* State: warning when projection passes the earliest unmet route cutoff; critical when it passes the last cutoff of the day.

**Dispatch**
* Hero: next route departure as a countdown, plus that route's order-readiness (orders ready / orders assigned).
* Secondary: routes remaining today with status (open / loading / departed) as a compact horizontal strip, worst-first.
* State: warning when a route's projected readiness passes its cutoff; critical when a cutoff is breached with orders not ready.
* Research note: no verified industry source covers cutoff visualisation (an explicit evidence gap); this design is derived from two verified building blocks, countdown-vs-target and projected-completion-vs-deadline.

**Automation** (drill: existing Reporting screens, which stay where they are)
* Hero: alert count by severity (shape + colour coded).
* Secondary: scan no-read rate today (%, one decimal) against its threshold (default 1%); equipment availability % (devices not in fault).
* State: from the highest active alert severity.

## 5. Dashboards menu

Five screens under a "Dashboards" sidebar section. Graphs and heroes only, no tables. Each caps at 5 to 7 metrics.

**Inbound dashboard**
Research (verified, NetSuite): split dock-to-stock into its two stages rather than one aggregate.
* Heroes: time to receive (arrival to receipt-confirmed, today's median), putaway time (receipt to stored, today's median), putaway backlog (HUs received but not yet stored, with oldest-age figure).
* Charts: receipts per hour today vs the hour-of-day norm; receive errors trend (14 days); open orders burn-down with projection.
* Default thresholds: putaway backlog age warning at 2 h, critical at 4 h.

**Outbound dashboard**
* Heroes: open orders with projected completion, lines per hour (current vs target), order accuracy proxy (shorts + corrections today), on-time-to-cutoff rate today.
* Charts: pick rate by hour vs norm; released-vs-shipped burn-down per wave/route; shorts trend (14 days).
* The verified practitioner shortlist for management level (throughput, order cycle time, accuracy, turnover, on-time shipment, dock-to-stock, picks per hour) is covered across landing + inbound + outbound rather than duplicated on every screen.

**Replenishment dashboard**
* Heroes: urgent replenishments (pick-face below minimum with open demand), projected stockouts within the next 2 h at current pick rates, open replenishment tasks with oldest age.
* Charts: urgency strip of affected pick locations, worst-first (time-to-stockout ascending), normalised bars; demand vs replenishment completion rate today.
* Research note: no verified industry precedent for replenishment-urgency presentation (evidence gap); time-to-stockout ranking is our own derivation, flagged as such.

**Stock dashboard (levels vs 90 days)**
* Heroes: location utilisation % (occupied / usable, overall and for the ASRS), stock value-free figures only (we have no costs): HU count, SKU count with stock.
* Charts: utilisation today drawn inside a shaded 90-day normal band (verified presentation pattern); 90-day utilisation line; intake-vs-consumption balance (net HU flow per day).
* Default thresholds: ASRS utilisation warning at 85%, critical at 95% (industry-typical honest-capacity guidance; not from a verified source, configurable).

**ABC movers dashboard**
* Research (verified): canonical ABC presentation is a Pareto: items ranked descending by movement with a cumulative curve and the A/B/C cut points marked. Default class boundaries: A = top items covering ~70 to 80% of movement (typically 10 to 20% of items), B = next ~15 to 20% (about 30% of items), C = the rest (~50% of items, ~5% of movement). Boundaries configurable; there is no fixed industry standard.
* Ranking metric: since openWCS has no item costs, rank by picks (lines) per SKU over the trailing 90 days. (The classic annual-usage-value formula did not survive verification and is cost-based anyway.)
* Heroes: count of A/B/C SKUs; risers (SKUs whose 14-day pick rate most exceeds their 90-day rate); fallers (the inverse).
* Charts: the Pareto curve with cut points; top-10 and bottom-10 movers as ranked horizontal bars with sparklines (no tables); riser/faller strips.
* Slotting tie-in: an A-class SKU slotted far from the outfeeds is exactly what the slotting config optimises, so the drill-down from a riser links to the slotting screen.

## 6. Refresh cadence and staleness

No verified industry numbers exist for dashboard refresh tiers (open question in the research). Our defaults, consistent with the rest of the UI:

* Landing tiles: poll every 15 s (same order as GTP/transport polling).
* Area dashboards: every 60 s; history charts on load.
* Every screen shows a last-updated stamp; if a poll fails twice the affected tile greys out and says "stale" rather than silently showing old numbers (an honest empty/stale state, matching the reporting screens).

## 7. Explicitly out of scope / refuted

* The strict "everything on one screen, never scroll" rule was refuted in verification; area dashboards may scroll, the landing page should still fit common laptop viewports.
* No radial gauges, no 3D chart effects, no decorative colour.
* No tables on any dashboard (spec rule); ranked bars and strips replace them.
* Dispatch-cutoff visuals, replenishment-urgency metrics, and all threshold defaults above are design derivations, not verified industry standards; they ship as configurable defaults.
