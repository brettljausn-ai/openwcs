# notification

Operator alerts, exceptions, andon notifications.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8088
- **Run:** `./gradlew :services:notification:bootRun`
- **Health:** `GET http://localhost:8088/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
