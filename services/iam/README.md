# iam

Users, MS Entra SSO + local accounts, RBAC with coded permissions (users -> roles -> permissions).

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8087
- **Run:** `./gradlew :services:iam:bootRun`
- **Health:** `GET http://localhost:8087/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
