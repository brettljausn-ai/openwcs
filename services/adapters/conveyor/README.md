# conveyor adapter

Conveyor device adapter — transport: Raw TCP / OPC-UA (PLC).

- **Language:** Go
- **Port:** 9091
- **Run:** `cd services/adapters/conveyor && go run .`
- **Health:** `GET http://localhost:9091/healthz`

Implements the uniform internal device contract (see [build.md](../../../build.md) §8).
