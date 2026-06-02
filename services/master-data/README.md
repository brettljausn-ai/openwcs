# master-data

Authoritative catalog: SKUs, UoM/bundles, barcodes & types, locations, equipment, warehouses, per-warehouse SkuProfiles, attribute schemas.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8081
- **Run:** `./gradlew :services:master-data:bootRun`
- **Health:** `GET http://localhost:8081/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
