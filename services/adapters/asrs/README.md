# asrs adapter

ASRS device adapter — transport: Raw TCP telegram (shuttle/crane).

- **Language:** Go
- **Port:** 9096
- **Run:** `cd services/adapters/asrs && go run .`
- **Health:** `GET http://localhost:9096/healthz`

Implements the uniform internal device contract (see [build.md](../../../build.md) §8).
