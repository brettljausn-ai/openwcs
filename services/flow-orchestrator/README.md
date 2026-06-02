# flow-orchestrator

Material-flow traffic controller: turns process steps into device tasks; routing, sequencing, contention, retries.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8085
- **Run:** `./gradlew :services:flow-orchestrator:bootRun`
- **Health:** `GET http://localhost:8085/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
