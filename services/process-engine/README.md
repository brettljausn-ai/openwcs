# process-engine

Stores admin-designed process definitions (goods-in, outbound, cycle count) and executes running instances.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8083
- **Run:** `./gradlew :services:process-engine:bootRun`
- **Health:** `GET http://localhost:8083/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
