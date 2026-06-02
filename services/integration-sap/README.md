# integration-sap

Host integration gateway / anti-corruption layer for SAP S/4HANA & SAP HANA: OData / BAPI / RFC / IDoc in and out; master-data and order sync translated to internal events.

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8089
- **Run:** `./gradlew :services:integration-sap:bootRun`
- **Health:** `GET http://localhost:8089/actuator/health`

See [build.md](../../build.md) for this service's responsibility, data ownership,
APIs, and events.
