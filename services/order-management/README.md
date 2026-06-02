# order-management

Inbound ASNs and outbound orders from the WMS; fulfilment lifecycle; WMS translation.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8084
- **Run:** `./gradlew :services:order-management:bootRun`
- **Health:** `GET http://localhost:8084/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
