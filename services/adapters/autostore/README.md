# autostore adapter

AutoStore device adapter — transport: REST (grid controller).

- **Language:** Go
- **Port:** 9094
- **Run:** `cd services/adapters/autostore && go run .`
- **Health:** `GET http://localhost:9094/healthz`

Implements the uniform internal device contract (see [build.md](../../../build.md) §8).
