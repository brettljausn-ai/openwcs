# ptl adapter

Pick-to-light (PTL) device adapter (transport: Pick-to-light controller, HTTP/serial).

- **Language:** Go
- **Port:** 9098
- **Run:** `cd services/adapters/ptl && go run .`
- **Health:** `GET http://localhost:9098/healthz`
- **State:** `GET http://localhost:9098/state` returns the currently lit lights, e.g. `{"lit": {"A-01": {"qty": 3, "color": "green"}}}`

Implements the uniform internal device contract (see [build.md](../../../build.md) §8). The gtp
service POSTs `/tasks` with `command: "ILLUMINATE"` (turn a light on at `payload.lightId` showing
`payload.qty`, optional `payload.color`) or `command: "CLEAR"` (turn it off).
