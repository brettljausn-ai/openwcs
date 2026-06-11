package main

import (
	"encoding/json"
	"net/http"
	"os"
	"strconv"
	"sync/atomic"
)

// config.go holds the emulator's live, runtime-tunable behaviour: simulated latency and fault rate.
// Both are atomics so GET/POST /config can change them during a demo while task handlers read them
// concurrently.

// latencyOverrideMs >= 0 forces every command's latency (ms); -1 uses the per-command defaults.
var latencyOverrideMs atomic.Int64

// faultEvery > 0 fails 1 in every N tasks (deterministic); 0 disables fault injection.
var faultEvery atomic.Int64

// initConfigFromEnv seeds the live config from env at startup:
//
//	OPENWCS_EMULATOR_LATENCY_MS  int >= 0 forces latency (0 = instant); unset = per-command defaults.
//	OPENWCS_EMULATOR_FAULT_RATE  int >= 0; N>0 fails 1 in every N tasks; unset/0 = no faults.
func initConfigFromEnv() {
	latencyOverrideMs.Store(-1)
	if n, ok := envInt("OPENWCS_EMULATOR_LATENCY_MS"); ok && n >= 0 {
		latencyOverrideMs.Store(n)
	}
	faultEvery.Store(0)
	if n, ok := envInt("OPENWCS_EMULATOR_FAULT_RATE"); ok && n >= 0 {
		faultEvery.Store(n)
	}
	recircEvery.Store(0)
	if n, ok := envInt("OPENWCS_EMULATOR_RECIRC_EVERY"); ok && n >= 0 {
		recircEvery.Store(n)
	}
}

func envInt(key string) (int64, bool) {
	v := os.Getenv(key)
	if v == "" {
		return 0, false
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return 0, false
	}
	return int64(n), true
}

// configView is the GET/POST /config body.
type configView struct {
	LatencyOverrideMs int64 `json:"latencyOverrideMs"`
	FaultEvery        int64 `json:"faultEvery"`
	RecircEvery       int64 `json:"recircEvery"`
}

// handleConfig serves GET /config (current values) and POST/PUT /config (update latency/fault live).
// Fields are optional: a nil field is left unchanged.
func handleConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeConfig(w)
	case http.MethodPost, http.MethodPut:
		var in struct {
			LatencyOverrideMs *int64 `json:"latencyOverrideMs"`
			FaultEvery        *int64 `json:"faultEvery"`
			RecircEvery       *int64 `json:"recircEvery"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}
		if in.LatencyOverrideMs != nil && *in.LatencyOverrideMs >= -1 {
			latencyOverrideMs.Store(*in.LatencyOverrideMs)
		}
		if in.FaultEvery != nil && *in.FaultEvery >= 0 {
			faultEvery.Store(*in.FaultEvery)
		}
		if in.RecircEvery != nil && *in.RecircEvery >= 0 {
			recircEvery.Store(*in.RecircEvery)
		}
		writeConfig(w)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func writeConfig(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(configView{
		LatencyOverrideMs: latencyOverrideMs.Load(),
		FaultEvery:        faultEvery.Load(),
		RecircEvery:       recircEvery.Load(),
	})
}
