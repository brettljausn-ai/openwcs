# txlog

Owns the append-only transaction log in shared Postgres; append, query, replay.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8086
- **Run:** `./gradlew :services:txlog:bootRun`
- **Health:** `GET http://localhost:8086/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
