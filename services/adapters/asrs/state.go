package main

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

// state.go holds the in-memory simulated device state and telemetry for the ASRS adapter.
// When the emulator is ON, handleTask updates the counters here and deviceLoop advances the
// telemetry snapshot. Everything is guarded by a single mutex and read under lock for /state.

// deviceState is the simulated ASRS crane/shuttle's running tallies.
type deviceState struct {
	mu sync.Mutex

	// Per-command counters and overall task tally.
	commandCounts map[string]int64
	totalTasks    int64
	lastCommand   string
	faults        int64

	// Family-specific counters.
	binsStored    int64
	binsRetrieved int64
	scanned       int64

	// Telemetry snapshot.
	startTime     time.Time
	ticks         int64
	throughput    int64
	lastHeartbeat string
}

var sim = &deviceState{
	commandCounts: map[string]int64{},
	startTime:     time.Now(),
}

// recordCommand bumps the counters for a successfully simulated command (caller has already
// verified the emulator is ON and the command is supported).
func (s *deviceState) recordCommand(command string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.commandCounts[command]++
	s.totalTasks++
	s.lastCommand = command
	switch command {
	case "STORE":
		s.binsStored++
	case "RETRIEVE":
		s.binsRetrieved++
	case "SCAN":
		s.scanned++
	}
}

// tick advances the telemetry snapshot once per deviceLoop iteration; every 7th tick bumps a
// simulated fault so the fault counter is exercised. It returns the post-tick ticks/throughput/
// faults so the caller can log them without re-taking the lock.
func (s *deviceState) tick(now time.Time) (ticks, throughput, faults int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ticks++
	s.throughput++
	s.lastHeartbeat = now.Format(time.RFC3339)
	if s.ticks%7 == 0 {
		s.faults++
	}
	return s.ticks, s.throughput, s.faults
}

// snapshot returns a JSON-ready view of the state read under lock.
func (s *deviceState) snapshot() map[string]interface{} {
	s.mu.Lock()
	defer s.mu.Unlock()
	counts := make(map[string]int64, len(s.commandCounts))
	for k, v := range s.commandCounts {
		counts[k] = v
	}
	return map[string]interface{}{
		"family":   family,
		"emulator": EmulatorMode(),
		"devices": map[string]interface{}{
			"totalTasks":    s.totalTasks,
			"lastCommand":   s.lastCommand,
			"commandCounts": counts,
			"faults":        s.faults,
			"binsStored":    s.binsStored,
			"binsRetrieved": s.binsRetrieved,
			"scanned":       s.scanned,
		},
		"telemetry": map[string]interface{}{
			"ticks":         s.ticks,
			"uptimeSeconds": int64(time.Since(s.startTime).Seconds()),
			"lastHeartbeat": s.lastHeartbeat,
			"throughput":    s.throughput,
		},
	}
}

// handleState serves GET /state with the in-memory device + telemetry snapshot.
func handleState(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(sim.snapshot())
}
