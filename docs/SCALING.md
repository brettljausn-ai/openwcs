# openWCS — horizontal scaling

How openWCS scales out to high order and device-traffic volumes. The request path of every service is
stateless (all durable state is in Postgres or Kafka), so services run as multiple replicas behind a
load balancer / Kubernetes `Service`. The few places that assumed a single instance have been made
replication-safe; this page records what scales and how.

Starter Kubernetes manifests live in [`deploy/k8s/`](../deploy/k8s/).

## What scales, and the guarantees behind it

| Area | Hazard if naively replicated | Mechanism that makes it safe | Scales? |
|---|---|---|---|
| REST request path (all services) | — | stateless; state in Postgres | ✅ replicas/HPA |
| Outbox relays — order-management, txlog | `@Scheduled` poller on every replica → **double-publish** events | **ShedLock** `@SchedulerLock` (one replica drains per tick); ordering preserved by single-writer + in-order send | ✅ |
| Off-peak jobs — slotting velocity/replenishment/reslot, counting sweep, host webhook | fire on every replica → **duplicate tasks/webhooks** | **ShedLock** `@SchedulerLock` | ✅ |
| Conveyor loop capacity — flow-orchestrator | count-then-enter race → **capacity exceeded** across replicas | **pessimistic row lock** on the loop (`lockByWarehouseIdAndCode`) makes check-and-enter atomic | ✅ |
| Stock reservation — inventory / allocation | concurrent allocations double-allocate | already serialized by a **pessimistic lock** on AVAILABLE stock rows; allocation idempotent on `orderRef` | ✅ |
| txlog→stock projection (inventory), velocity learner (slotting) | duplicate event processing | Kafka **consumer group** (competing consumers) + idempotent apply on `event_id` | ✅ up to topic partitions |
| Cubing (allocation) | — | pure per-request computation | ✅ |
| Conveyor scan routing (flow-orchestrator) | — | stateless; Dijkstra per scan | ✅ (Postgres is the eventual limit) |
| **conveyor-sniffer** | a controller's TCP telegram stream **fragments** if split across replicas | **single instance** (or one-per-controller / sticky L4) | ⚠️ not load-balanced |

## ShedLock (scheduled-job leader election)

order-management, txlog, slotting, counting, and integration-host each carry ShedLock (JDBC). A
`@SchedulerLock`-annotated `@Scheduled` method acquires a cluster-wide lock — a row in that service's
own `<schema>.shedlock` table (added by a Flyway migration; see each service's `ShedLockConfig`) — so
it runs on only one replica per fire. The relays use a short `lockAtMostFor` (1 min) for fast failover
if a holder dies; daily jobs use the service default. ShedLock deliberately does **not** wrap the job
in a transaction, so the daily sweeps keep their per-warehouse commit behaviour.

## Operational must-dos

1. **Kafka partition count caps consumer scaling.** The inventory stock projection
   (`group.id = inventory-stock-projection`) and the slotting velocity learner
   (`slotting-velocity-learner`) scale only up to the partition count of `txlog.stream`. Provision
   that topic with ≥ the desired max replica count for those consumers; replicas beyond the partition
   count idle.
2. **Do not round-robin the conveyor-sniffer.** It is pinned to one replica (`deploy/k8s/adapters.yaml`).
   Scale by running one instance per controller/site (distinct `SNIFFER_LISTEN`/`ALLOWED_IPS`) or with
   a sticky (session-affinity) L4 load balancer.

## Known throughput tuning (not correctness — future work)

- **Scan hot path:** flow-orchestrator rebuilds the per-warehouse routing graph and runs Dijkstra on
  every scan. Correct and indexed, but at very high scan rates Postgres is the bottleneck. A future
  optimization is to cache the topology graph per warehouse and rebuild it on topology change instead
  of reading edges per scan. Scaling replicas + Postgres capacity covers it until then.
- **Outbox relays are single-writer** (by ShedLock). If relay throughput ever becomes the limit,
  switch to a partitioned `FOR UPDATE SKIP LOCKED` drain keyed per stream (preserves per-stream order
  while allowing parallel workers).
