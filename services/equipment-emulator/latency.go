package main

import "time"

// latency.go gives the emulator a simulated per-family/command processing time, so device tasks no
// longer complete instantly. The device contract is still synchronous (flow-orchestrator blocks on
// the HTTP response), so durations are kept modest — a non-blocking async contract is the follow-up
// (EMULATOR-CONSOLIDATION.md, Phase 3b). The live override lives in config.go (latencyOverrideMs).

// defaultLatency is the simulated processing time per family+command; misses fall back to
// fallbackLatency.
// RELOCATE/BIN_RELOCATE (ADR-0009 dig-out) is a full shuttle move (pick the blocker, travel to the
// target channel on the same level, drop), so it is deliberately slower than a plain retrieve — a
// blocked retrieve becomes visibly slower, with the reason in the HU trace.
var defaultLatency = map[string]map[string]time.Duration{
	"ASRS":      {"STORE": 900 * time.Millisecond, "RETRIEVE": 900 * time.Millisecond, "RELOCATE": 3000 * time.Millisecond, "SCAN": 150 * time.Millisecond},
	"CONVEYOR":  {"CONVEY": 600 * time.Millisecond, "DIVERT": 400 * time.Millisecond, "MERGE": 400 * time.Millisecond, "SCAN": 150 * time.Millisecond},
	"AMR":       {"TRANSPORT": 1200 * time.Millisecond, "MOVE": 600 * time.Millisecond, "SCAN": 150 * time.Millisecond},
	"AUTOSTORE": {"BIN_STORE": 800 * time.Millisecond, "BIN_RETRIEVE": 800 * time.Millisecond, "BIN_RELOCATE": 3000 * time.Millisecond, "SCAN": 150 * time.Millisecond},
}

const fallbackLatency = 300 * time.Millisecond

// commandLatency returns the simulated processing time for a family+command, honouring the live
// override (latencyOverrideMs >= 0 forces every command; -1 uses the per-command defaults).
func commandLatency(family, command string) time.Duration {
	if ov := latencyOverrideMs.Load(); ov >= 0 {
		return time.Duration(ov) * time.Millisecond
	}
	if cmds, ok := defaultLatency[family]; ok {
		if d, ok := cmds[command]; ok {
			return d
		}
	}
	return fallbackLatency
}
