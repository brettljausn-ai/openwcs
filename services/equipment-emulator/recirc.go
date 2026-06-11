package main

import (
	"sync/atomic"
	"time"
)

// recirc.go models conveyor loop recirculation (ADR-0007 Phase 3c-2). A recirculating conveyor can
// fail to divert a tote to its destination on a given pass (destination buffer full / missed window),
// sending it around the loop again before it arrives. When recircEvery > 0, every Nth CONVEY task
// recirculates once, adding a loop's worth of travel time — so arrival order visibly diverges from
// request/dispatch order (requirement R2). Deterministic (a counter, not randomness) so it is testable.

// recircEvery: 0 = never recirculate; N = every Nth CONVEY task recirculates once. Seeded from
// OPENWCS_EMULATOR_RECIRC_EVERY and tunable live via /config.
var recircEvery atomic.Int64

// conveySeq counts CONVEY tasks, advanced only while recirculation is enabled so toggling it on is
// predictable.
var conveySeq atomic.Int64

// loopLatency is the simulated travel time for one full loop pass. A var (not const) so tests can
// shrink it.
var loopLatency = 1200 * time.Millisecond

// conveyJourney returns the extra recirculation travel time and the ordered decision points for a
// CONVEY task: a "RECIRCULATED" decision per missed divert, then a final "DIVERTED" decision when the
// tote leaves the loop at its destination. The decisions are reported to flow and written to the HU
// transport trace (R4).
func conveyJourney() (extra time.Duration, decisions []map[string]interface{}) {
	recirculations := 0
	if n := recircEvery.Load(); n > 0 && conveySeq.Add(1)%n == 0 {
		recirculations = 1
	}
	for i := 0; i < recirculations; i++ {
		extra += loopLatency
		decisions = append(decisions, map[string]interface{}{
			"point":    "sorter",
			"event":    "RECIRCULATED",
			"decision": "missed divert (destination busy) — looping",
		})
	}
	decisions = append(decisions, map[string]interface{}{
		"point":    "sorter",
		"event":    "DIVERTED",
		"decision": "diverted to destination",
	})
	return extra, decisions
}
