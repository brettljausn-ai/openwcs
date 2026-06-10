package main

import (
	"os"
	"strconv"
	"time"
)

// latency.go gives the emulator a simulated per-family/command processing time, so device tasks no
// longer complete instantly. The device contract is still synchronous (flow-orchestrator blocks on
// the HTTP response), so durations are kept modest — a non-blocking async contract is the follow-up
// (EMULATOR-CONSOLIDATION.md, Phase 3b).

// latencyOverrideMs, when >= 0, forces every command's simulated latency (ms); -1 uses the
// per-command defaults. Set from OPENWCS_EMULATOR_LATENCY_MS at startup; tests set it to 0.
var latencyOverrideMs = -1

// defaultLatency is the simulated processing time per family+command; misses fall back to
// fallbackLatency.
var defaultLatency = map[string]map[string]time.Duration{
	"ASRS":      {"STORE": 900 * time.Millisecond, "RETRIEVE": 900 * time.Millisecond, "SCAN": 150 * time.Millisecond},
	"CONVEYOR":  {"CONVEY": 600 * time.Millisecond, "DIVERT": 400 * time.Millisecond, "MERGE": 400 * time.Millisecond, "SCAN": 150 * time.Millisecond},
	"AMR":       {"TRANSPORT": 1200 * time.Millisecond, "MOVE": 600 * time.Millisecond, "SCAN": 150 * time.Millisecond},
	"AUTOSTORE": {"BIN_STORE": 800 * time.Millisecond, "BIN_RETRIEVE": 800 * time.Millisecond, "SCAN": 150 * time.Millisecond},
}

const fallbackLatency = 300 * time.Millisecond

// commandLatency returns the simulated processing time for a family+command, honouring the override.
func commandLatency(family, command string) time.Duration {
	if latencyOverrideMs >= 0 {
		return time.Duration(latencyOverrideMs) * time.Millisecond
	}
	if cmds, ok := defaultLatency[family]; ok {
		if d, ok := cmds[command]; ok {
			return d
		}
	}
	return fallbackLatency
}

// initLatencyFromEnv reads OPENWCS_EMULATOR_LATENCY_MS: an int >= 0 forces every command to that many
// ms (0 = instant); unset/blank/invalid keeps the per-command defaults.
func initLatencyFromEnv() {
	v := os.Getenv("OPENWCS_EMULATOR_LATENCY_MS")
	if v == "" {
		return
	}
	if n, err := strconv.Atoi(v); err == nil && n >= 0 {
		latencyOverrideMs = n
	}
}
