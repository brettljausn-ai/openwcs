# notification

Threshold-based operational alerting (Dashboards & alerting epic). A periodic, ShedLock-guarded
evaluator pulls each warehouse's KPIs from the sibling services and compares them against the
master-data alert thresholds; breaches open alerts, recoveries clear them. OPEN/CLEAR transitions are
delivered best-effort over email (SMTP) and an optional webhook.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8088
- **Store:** service-local `notification` Postgres schema (Flyway); `alert_event` ledger + `shedlock`
- **Run:** `./gradlew :services:notification:bootRun`
- **Health:** `GET http://localhost:8088/actuator/health`

## Evaluator

Runs every `OPENWCS_NOTIFICATION_EVALUATE_INTERVAL_MS` (default 60s, ShedLock-guarded so only one
replica fires per tick). Per warehouse it reads:

| Source | Endpoint | Metric → threshold | Area |
| --- | --- | --- | --- |
| flow | `/api/flow/reports/automation-summary` | `scanNoReadPctToday` → `scanNoReadPct` | SCAN |
| order-management | `/api/orders/reports/dashboard` | `inbound.husErrorsToday` → `receiveErrorsDay` | RECEIVING |
| inventory | `/api/inventory/reports/dashboard` | `asrsUtilisationPct` → `asrsUtilisationPct` | ASRS |
| inventory | `/api/inventory/reports/dashboard` | `putawayBacklog.oldestAgeMin` → `putawayBacklogAgeMin` | PUTAWAY |
| allocation | `/api/allocation/reports/stock-blocking` | `blockedLines/openOutboundLines %` → `blockedLinesPct` | ALLOCATION |
| slotting | `/api/slotting/replenishment/dashboard` | `oldestAgeMin` → `replenishmentOldestAgeMin` | REPLENISHMENT |

Thresholds come from master-data `GET /api/master-data/alert-thresholds`. A value over its threshold
opens a WARNING (CRITICAL when ≥ 1.5×); deduped by `(warehouse, area, metric)` so a sustained breach
never duplicates; dropping back under clears it. Every source call is best-effort — a source being
down logs a WARN and skips that metric, never crashing the loop. Warehouses to scan come from
`OPENWCS_NOTIFICATION_WAREHOUSE_IDS` (comma-separated UUIDs) or, when blank, are discovered from
master-data `GET /api/master-data/warehouses`.

## API (via the gateway: `/api/notification`)

- `GET /api/notification/alerts?warehouseId=<uuid>` — active (OPEN/ACKED) alerts (VIEW).
- `POST /api/notification/alerts/{id}/ack` — acknowledge (SUPERVISOR/ADMIN; actor = `X-Auth-User`).

## Delivery

Both channels are best-effort and isolated from evaluation:

- **Email** (SMTP): set `SPRING_MAIL_HOST` + `OPENWCS_NOTIFICATION_MAIL_TO`. Unconfigured → log-and-skip.
- **Webhook**: set `ALERT_WEBHOOK_URL`. Unset → skipped.

See [build.md](../../build.md) for this service's responsibility, data ownership, APIs, and events.
