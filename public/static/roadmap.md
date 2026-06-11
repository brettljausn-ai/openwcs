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
- [done] ASRS storage logic :: Block-level put-away, re-slotting, in-aisle depth and dual-cycle behaviour, plus empty-HU management for shuttle / crane / AutoStore / AMR-GTP.
- [done] Goods-to-person stations :: Present one stock HU and put-to-light fills many order destinations most-needed-first; ORDER_LOCATION and PUT_WALL share one engine.
- [done] Slotting & replenishment :: Weighted, configurable put-away scoring with self-taught ABC velocity, plus min/max refills, off-peak top-off and direct-to-pick cross-docking.
- [done] Allocation, cubing & batch :: Pick-location allocation with UoM breakdown, largest-first multi-size cubing into shippers, per-shipper dispatch labels, and batch picking.
- [done] Event-sourced inventory :: Real-time stock projected from an append-only transaction log — location-scoped availability/ATP, reservations under a lock, idempotent and rebuildable.
- [done] Admin-designed BPMN :: Model goods-in, outbound and cycle-count flows on an embedded Flowable engine, deploy them, and have service tasks originate real WCS work.
- [done] Canonical Host API :: One vendor-neutral API for orders, ASNs, SKUs and adjustments in; confirmations out via cursor feed or webhooks; SAP and Manhattan adapters translate in.
- [done] Security — JWT · RBAC · Keycloak :: Gateway JWT validation and per-endpoint role-based access from a shared catalog, with Keycloak — all toggleable from simple to locked-down.
- [done] Horizontal scaling :: Every service is stateless and replica-safe — relays and schedulers run across replicas, with Kubernetes manifests for scaling out behind any load balancer.
- [done] Hardware emulator mode :: Every device adapter (conveyors, ASRS, AMR, AutoStore) simulates its machines and telemetry behind a single admin toggle — run the full automation flow with zero physical hardware for evaluation, onboarding, or CI.
- [done] Live 3D digital twin :: The saved layout rendered live in the browser: equipment coloured idle / running / faulted from real device tasks, totes replaying the actual scan trail, and storage fill shown at cell level in the ASRS rack.
- [done] Cycle counting :: Count tasks with blind and variance modes and ABC-cadence scheduling, at-station blind counting via the GTP console, and a standalone count-capture screen with variances, recounts and reconciliation.

## In progress
> Active development

- [active] Pick execution :: Operator and goods-to-person pick confirmation, pick-by-light / voice / RF, and the GTP-station pick workflow on top of today's allocation and planning.

## Next up
> Designed, build queued

- [planned] KPI dashboards :: Throughput, dwell, utilisation and SLA dashboards over a metrics store — operational visibility for floor and management.

## Exploring
> On the horizon

- [exploring] AMR fleet integration :: Device adapters and orchestration for autonomous mobile robot fleets over the uniform device contract, with live fleet positions joining the digital twin.
- [exploring] AutoStore integration :: A native AutoStore adapter so grid storage joins conveyors and ASRS behind the same vendor-neutral contract, with grid and port status joining the digital twin.
