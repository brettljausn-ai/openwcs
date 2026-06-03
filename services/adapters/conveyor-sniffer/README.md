# conveyor-sniffer

Capture front-end for conveyor **topology learning**. It ingests vendor scan telegrams from the
conveyor network — accepted only from configured source IPs — decodes them into normalized scans
`{node, barcode}`, and forwards them to the WCS:

```
conveyor controllers ──(telegrams)──►  conveyor-sniffer  ──►  POST /api/flow/conveyor/observations  (flow-orchestrator)
   (defined IPs)                        decode + allowlist        → WCS infers the topology
```

The WCS then infers candidate nodes / segments / targets, which an admin confirms in the
topology editor (see the **Equipment Integration** wiki page).

## Seams

- **Decoder** (`Decoder`): per-vendor telegram parsing. Ships with a `csv` decoder (`NODE,BARCODE`
  lines); add real telegram/OPC-UA decoders behind the same interface.
- **Capture source**: today a controller push stream over TCP (`SNIFFER_LISTEN`). A passive
  libpcap mirror-port tap is a drop-in source later (it would feed the same Decoder/Forwarder).

## Config (env)

| Var | Default | Purpose |
|---|---|---|
| `PORT` | `9095` | health/info HTTP port |
| `SNIFFER_LISTEN` | `:9200` | TCP address telegrams arrive on |
| `WCS_OBSERVATIONS_URL` | `http://localhost:8085/api/flow/conveyor/observations` | WCS endpoint |
| `WAREHOUSE_ID` | — | warehouse the observations belong to |
| `ALLOWED_IPS` | _(empty = any)_ | CSV allowlist of telegram source IPs |
| `DECODER` | `csv` | telegram decoder |

Stdlib-only Go; `go build ./... && go test ./...`.
