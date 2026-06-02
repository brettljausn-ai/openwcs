# integration-manhattan

Host integration gateway / anti-corruption layer for Manhattan Active (WM / Omni): REST APIs in and out; inbound ASNs, outbound orders, and stock sync translated to internal events.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8090
- **Run:** `./gradlew :services:integration-manhattan:bootRun`
- **Health:** `GET http://localhost:8090/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
