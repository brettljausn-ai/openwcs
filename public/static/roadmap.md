<!--
  ============================================================================
  openWCS ROADMAP  —  single source of truth for the public roadmap page.
  Rendered live by public/roadmap.html (no build step, no server).
  ============================================================================

  ⚠️  CLAUDE / docs-agent: KEEP THIS FILE CURRENT.
      This file *is* the roadmap — public/roadmap.html reads it verbatim and
      draws the timeline from it. Whenever a capability's status changes
      (a roadmap item ships, work starts on it, or new work is planned),
      update the matching line below in the same PR as the code. Honesty rule:
      never list something as "done" before it is actually built end-to-end.

  ----------------------------------------------------------------------------
  HOW TO EDIT  —  it is plain text, edit it like a list:

    • "## Heading" starts a new timeline phase (a stage on the page).
    • An optional "> caption" line right under a heading adds a small subtitle
      (e.g. a timeframe). One per phase.
    • Every "- " line under a phase is one roadmap item, written as:

          - [status] Title :: One short sentence describing it.

      where [status] is exactly one of:
          done       → already built and runnable today
          active     → in progress right now
          planned    → designed, build queued
          exploring  → on the horizon / under consideration

    • Display order = file order. Reorder freely.
    • Lines that don't match these shapes are ignored, so comments are safe.
  ----------------------------------------------------------------------------
-->

## Shipped
> Built and runnable today

- [done] Conveyor routing :: Vendor-neutral topology graph with per-scan next-hop pathfinding, loop limits, PLC controllers, and topology learned from live scan traffic.
- [done] ASRS storage logic :: Block-level put-away, re-slotting, in-aisle depth and dual-cycle behaviour, WCS-owned multi-deep channel dig-out (relocation chain before a blocked retrieve), plus empty-HU management for shuttle / crane / AutoStore / AMR-GTP.
- [done] Goods-to-person stations :: Configurable pick layouts (ONE_TO_ONE, ONE_TO_N batch slots, PUT_WALL) with physical pick-to-light driven through a PTL adapter; one stock HU fans out most-needed-first across destinations, ORDER_LOCATION and PUT_WALL on one engine.
- [done] Slotting & replenishment :: Weighted, configurable put-away scoring with self-taught ABC velocity, plus min/max refills, off-peak top-off and direct-to-pick cross-docking.
- [done] Allocation, cubing & batch :: Pick-location allocation with UoM breakdown, largest-first multi-size cubing into shippers, per-shipper dispatch labels, and batch picking.
- [done] Event-sourced inventory :: Real-time stock projected from an append-only transaction log — location-scoped availability/ATP, reservations under a lock, idempotent and rebuildable.
- [done] Admin-designed BPMN :: Model goods-in, outbound and cycle-count flows on an embedded Flowable engine, deploy them, and have service tasks originate real WCS work.
- [done] Canonical Host API :: One vendor-neutral API for orders, ASNs, SKUs and adjustments in; confirmations out via cursor feed or webhooks; SAP and Manhattan adapters translate in.
- [done] Security — JWT · RBAC · Keycloak :: Gateway JWT validation and per-endpoint role-based access from a shared catalog, with Keycloak — all toggleable from simple to locked-down.
- [done] Horizontal scaling :: Every service is stateless and replica-safe — relays and schedulers run across replicas, with Kubernetes manifests for scaling out behind any load balancer.
- [done] Hardware emulator mode :: Every device adapter (conveyors, ASRS, AMR, AutoStore) simulates its machines and telemetry behind a single admin toggle — run the full automation flow with zero physical hardware for evaluation, onboarding, or CI.
- [done] Live 3D digital twin :: The saved layout rendered live in the browser: equipment coloured idle / running / faulted from real device tasks, totes replaying the actual scan trail, storage fill shown at cell level in the ASRS rack, plus a live AMR fleet (robots coloured by status, carried HU), AutoStore ports (busy / idle) with a grid fill % stat, and live ASRS cranes that glide and lift in the rack, all from emulator telemetry. AMR robots and ASRS cranes are interpolated per frame for metre-exact continuous motion.
- [done] Operator pick confirmation :: A **scanner-first**, keyboard-wedge-ready picking console over the order-management pick queue: a big glove-friendly next-pick card (location, SKU code / name / image, quantity), auto-confirm at full-qty scan with mismatch warning + vibrate, manual Confirm / Short still available, an **offline confirm queue** that survives a Wi-Fi blip, and an **installable PWA** so the app runs standalone on rugged handhelds. Pick-to-light is now live via a PTL adapter; voice guidance is the one remaining planned seam.
- [done] Cycle counting :: Count tasks with blind and variance modes and ABC-cadence scheduling, at-station blind counting via the GTP console, and a standalone count-capture screen with variances, recounts and reconciliation.
- [done] Operational reporting :: A Reporting section with five screens: scan quality per scan point with predictive error trends, a 3D conveyor traffic heatmap, ASRS storage density and movements with 90-day history and 14-day forecasts, per-SKU stock availability split, and inbound/outbound flow with hour-of-day peak maps.
- [done] Multilanguage UI :: The operator and management SPA ships in English, German, French, Spanish, Chinese and Brazilian Portuguese with a per-user language preference — no add-on required.
- [done] Dashboards & alerting :: A landing situation dashboard (Stock-blocking, Inbound, Outbound, Dispatch, Automation, Putaway heroes) plus five deeper screens (inbound/outbound/replenishment/stock/ABC movers with SLA metrics, dock-to-stock timing, and a Pareto chart); a full-screen andon board; ISA-18.2 alert-system-health screen; and threshold-based operator alerting (email + webhook delivery, deduped and cleared automatically).
- [done] AI assistant :: An in-app chat widget answers questions about orders, transports, stock and handling units by calling the WCS's own read-only APIs under the user's permissions — powered by the Anthropic Claude Messages API tool-use loop. Configured via Settings → AI Assistant (Anthropic key, model, enable/disable). Disabled until an admin sets a key.
- [done] Configurable handheld process designer :: Design multi-step handheld operator processes in a WYSIWYG editor — screen flows (text, number, scan-verify with barcode/SKU/location resolution, acknowledge, choice), branch conditions, curated task steps calling live WCS endpoints, version management with import/export, and a sandboxed JS scripting escape hatch with design-time AI task-assist. The PWA runtime walks steps on-device with offline checkpoint queuing; active processes appear as tiles on the installed handheld operator menu alongside Picking and Stock Check.
- [done] Pick guidance & GTP workflow :: Three pick layouts (ONE_TO_ONE, ONE_TO_N batch slots, PUT_WALL) with physical pick-to-light driven through a dedicated PTL adapter; the full GTP station pick workflow — present, put-to-light, confirm, auto-release — is live on top of scanner-first RF picking, allocation and planning.

## In progress
> Active development

## Next up
> Designed, build queued

## Exploring
> On the horizon

- [exploring] Voice pick guidance :: Voice prompting for hands-free operator guidance layered on the existing pick workflow; the hardware seam is a planned adapter.
- [exploring] AMR fleet integration :: Real device adapters and orchestration for autonomous mobile robot fleets over the uniform device contract; the twin already renders a live AMR fleet from emulator telemetry today.
- [exploring] AutoStore integration :: A native AutoStore adapter so grid storage joins conveyors and ASRS behind the same vendor-neutral contract; the twin already shows AutoStore ports and grid fill from emulator telemetry today.
