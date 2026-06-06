# openWCS on Kubernetes — starter manifests

Plain-YAML starter manifests for running openWCS as a horizontally-scaled deployment. These are a
**starting point**, not a turnkey production install — set your image registry, secrets, ingress, and
resource sizing for your cluster before relying on them.

```
deploy/k8s/
├── namespace.yaml      # the openwcs namespace
├── config.yaml         # shared ConfigMap (service DNS URLs, Kafka, security) + DB Secret (placeholder)
├── services.yaml       # Deployment + Service for every Java service
├── adapters.yaml       # Deployment + Service for the Go device adapters (sniffer pinned to 1 replica)
└── hpa.yaml            # HorizontalPodAutoscalers for the high-traffic services
```

Apply with `kubectl apply -f deploy/k8s/` (after editing `config.yaml` and the image registry).

## What can scale, and why

The request path of every Java service is stateless (state lives in Postgres), so they load-balance
behind a `Service` and scale via replicas/HPA. The two things that are **not** naturally
replication-safe were fixed in code so that the whole estate can scale:

| Concern | Before | Fix (in this branch) | Scale? |
|---|---|---|---|
| Outbox relays (order-management, txlog) | `@Scheduled` poller on every replica → double-publish | **ShedLock** `@SchedulerLock` → one replica drains per tick | ✅ replicate freely |
| Off-peak jobs (slotting velocity/replenishment/reslot, counting sweep, host webhook) | fire on every replica → duplicate tasks/webhooks | **ShedLock** `@SchedulerLock` | ✅ replicate freely |
| Conveyor loop capacity (flow-orchestrator) | count-then-enter race → capacity exceeded | **pessimistic row lock** on the loop | ✅ replicate freely |
| Stock reservation (inventory/allocation) | — | already serialized by a pessimistic stock lock | ✅ replicate freely |
| txlog→stock projection (inventory), velocity learner (slotting) | Kafka consumers | a **fixed consumer group** → competing consumers | ✅ scales up to the topic partition count |

### Two things to get right operationally

1. **Kafka partitions gate consumer scaling.** The inventory stock projection
   (`group.id = inventory-stock-projection`) and the slotting velocity learner
   (`slotting-velocity-learner`) scale only up to the **partition count** of `txlog.stream`. Create
   that topic with at least as many partitions as the max replica count you want for those consumers
   (e.g. 12). Extra replicas beyond the partition count sit idle.
2. **The conveyor-sniffer cannot be load-balanced.** It accepts a long-lived TCP telegram stream per
   conveyor controller; splitting that stream across replicas fragments telegrams. It is pinned to
   **`replicas: 1`** with `Recreate` strategy (see `adapters.yaml`). To scale sniffing, run one
   instance per controller/site (separate Deployments with distinct `SNIFFER_LISTEN`/allowlists) or
   put a session-affinity (sticky) L4 load balancer in front — not round-robin.

## The scan hot path (flow-orchestrator)

flow-orchestrator is stateless and replicates freely, and the loop-capacity race is fixed. Under very
high scan rates the bottleneck is Postgres (each scan reads topology + the HU route and writes the
route). Mitigations when you get there: scale flow-orchestrator replicas (CPU-bound Dijkstra), give
Postgres enough connections/IOPS, and — as a later optimization — cache the per-warehouse topology
graph and rebuild it on topology change instead of reading edges every scan. Not required for
correctness; it's a throughput tuning step.

## Conventions baked into the manifests

- **Images:** `REGISTRY/openwcs-<service>:TAG` — replace `REGISTRY`/`TAG` (e.g. with `kustomize edit
  set image` or `sed`). The repo builds these images from each service dir (see `scripts/deploy.sh`).
- **Inter-service URLs** use Kubernetes DNS (`http://master-data:8081`, …) — identical to the compose
  service names, so no app config changes are needed. They live in the shared ConfigMap.
- **Infra (Postgres, Kafka, Keycloak)** are referenced by the Services `postgres`, `kafka`,
  `keycloak`. These manifests assume those exist in-cluster (or are `ExternalName` Services pointing
  at managed equivalents) — they are intentionally **not** included here; run Postgres/Kafka via your
  preferred operator or a managed service.
- **Probes:** Java → `GET /actuator/health/{liveness,readiness}`; Go adapters → `GET /healthz`,
  `/readyz`.
- **Replicas:** stateless services default to 2; high-traffic ones get an HPA in `hpa.yaml`
  (2→10 on CPU). The sniffer is fixed at 1.
