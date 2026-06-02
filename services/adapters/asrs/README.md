# asrs adapter

ASRS device adapter — transport: Raw TCP telegram (shuttle/crane).

- **Language:** Go
- **Port:** 9092
- **Run:** `cd services/adapters/asrs && go run .`
- **Health:** `GET http://localhost:9092/healthz`

Implements the uniform internal device contract (see [build.md](../../../build.md) §8).
