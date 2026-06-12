package main

import (
	"encoding/json"
	"log"
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
//	OPENWCS_EMULATOR_SPEED_MPS   float > 0 conveyor speed for live walks; unset = 0.5 m/s (ADR-0008).
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
	setSpeedMps(defaultSpeedMps)
	if f, ok := envFloat("OPENWCS_EMULATOR_SPEED_MPS"); ok && f > 0 {
		setSpeedMps(f)
	}
	log.Printf("%s: config seeded from env: latencyOverrideMs=%d (-1 = per-command defaults), faultEvery=%d (0 = no fault injection), recircEvery=%d (0 = no forced recirculation), speedMps=%.2f",
		serviceName, latencyOverrideMs.Load(), faultEvery.Load(), recircEvery.Load(), speedMps())
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

func envFloat(key string) (float64, bool) {
	v := os.Getenv(key)
	if v == "" {
		return 0, false
	}
	f, err := strconv.ParseFloat(v, 64)
	if err != nil {
		return 0, false
	}
	return f, true
}

// configView is the GET/POST /config body.
type configView struct {
	LatencyOverrideMs int64   `json:"latencyOverrideMs"`
	FaultEvery        int64   `json:"faultEvery"`
	RecircEvery       int64   `json:"recircEvery"`
	SpeedMps          float64 `json:"speedMps"`
}

// handleConfig serves GET /config (current values) and POST/PUT /config (update latency/fault live).
// Fields are optional: a nil field is left unchanged.
func handleConfig(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeConfig(w)
	case http.MethodPost, http.MethodPut:
		var in struct {
			LatencyOverrideMs *int64   `json:"latencyOverrideMs"`
			FaultEvery        *int64   `json:"faultEvery"`
			RecircEvery       *int64   `json:"recircEvery"`
			SpeedMps          *float64 `json:"speedMps"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			log.Printf("%s: WARNING config update rejected: body is not valid JSON (%v); live config unchanged", serviceName, err)
			http.Error(w, "invalid request body", http.StatusBadRequest)
			return
		}
		// Each accepted change is logged old -> new so the daily log explains why task behaviour
		// (latency, faults, recirculation, walk speed) shifted mid-demo.
		if in.LatencyOverrideMs != nil && *in.LatencyOverrideMs >= -1 {
			if old := latencyOverrideMs.Swap(*in.LatencyOverrideMs); old != *in.LatencyOverrideMs {
				log.Printf("%s: config change via /config: latencyOverrideMs %d -> %d (-1 = per-command defaults; >=0 forces every command and edge to that many ms)",
					serviceName, old, *in.LatencyOverrideMs)
			}
		}
		if in.FaultEvery != nil && *in.FaultEvery >= 0 {
			if old := faultEvery.Swap(*in.FaultEvery); old != *in.FaultEvery {
				log.Printf("%s: config change via /config: faultEvery %d -> %d (0 = no fault injection; N fails 1 in every N tasks)",
					serviceName, old, *in.FaultEvery)
			}
		}
		if in.RecircEvery != nil && *in.RecircEvery >= 0 {
			if old := recircEvery.Swap(*in.RecircEvery); old != *in.RecircEvery {
				log.Printf("%s: config change via /config: recircEvery %d -> %d (0 = no forced recirculation; N recirculates every Nth CONVEY once)",
					serviceName, old, *in.RecircEvery)
			}
		}
		if in.SpeedMps != nil && *in.SpeedMps > 0 {
			if old := speedMps(); old != *in.SpeedMps {
				setSpeedMps(*in.SpeedMps)
				log.Printf("%s: config change via /config: speedMps %.2f -> %.2f (live conveyor walk edge-travel speed)",
					serviceName, old, *in.SpeedMps)
			}
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
		SpeedMps:          speedMps(),
	})
}
