# Contracts

Contract-first source of truth shared across services.

- `events/` — Avro/Protobuf schemas for domain events on the Kafka backbone
  (registered in the Schema Registry; versioned & backward-compatible).
- `openapi/` — OpenAPI specs for each service's REST API; generate typed
  server stubs and the SPA client from these.

See build.md §9 (events) and §14 (build & developer workflow).
