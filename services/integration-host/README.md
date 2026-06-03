# integration-host — canonical openWCS Host API

The single, vendor-neutral API a host system (WMS/ERP) integrates against. A host pushes
**outbound orders** and **ASNs** in, and pulls **confirmations** out; vendor adapters
(`integration-sap`, `integration-manhattan`) translate their native protocols into this same
API, so a host integrates once against a stable contract.

- `POST /api/host/orders` — create an outbound order (→ order-management OUTBOUND).
- `POST /api/host/asns` — create an ASN / expected receipt (→ order-management INBOUND).
- `GET  /api/host/confirmations?cursor=` — pull confirmations (receipts, picks, shipments,
  stock changes) from the transaction log; returns `nextCursor` for the next poll.

Contract: [`contracts/openapi/host-api.yaml`](../../contracts/openapi/host-api.yaml). Port 8092.

Roadmap: webhook (push) delivery of confirmations; master-data and inventory-adjustment
intake; idempotency keys.
