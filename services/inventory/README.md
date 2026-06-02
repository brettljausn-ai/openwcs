# inventory

Real-time stock (SKU x batch/lot x location x HU x status) as a projection of the transaction log; reservations; FEFO/FIFO.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8082
- **Run:** `./gradlew :services:inventory:bootRun`
- **Health:** `GET http://localhost:8082/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
