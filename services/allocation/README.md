# allocation

Outbound fulfilment prep (ADR 0002): **allocation** (reserve order lines against
pick-able locations, with a pick-type UoM breakdown) and **cubing** (determine the
shippers an order needs, or honour a host 1:1 cube instruction).

- **Language:** Java 21 + Spring Boot 3
- **Port:** 8091
- **Run:** `./gradlew :services:allocation:bootRun`
- **Health:** `GET http://localhost:8091/actuator/health`

Calls `master-data` (fulfilment config, shippers, PICK locations, SKU UoMs) and
`inventory` (location-scoped availability + reservations). See
[build.md](../../build.md) and [docs/adr/0002-outbound-allocation-and-cubing.md](../../docs/adr/0002-outbound-allocation-and-cubing.md).
