package main

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

// state.go holds the in-memory simulated state and telemetry for all families. handleTask updates
// the per-family counters; telemetryLoop advances the snapshot. Guarded by a single mutex.

// familyState is one family's running command tallies.
type familyState struct {
	commandCounts map[string]int64
	totalTasks    int64
	lastCommand   string
}

// deviceState is the emulator's whole-fleet state: per-family tallies plus a telemetry snapshot.
type deviceState struct {
	mu sync.Mutex

	families   map[string]*familyState
	totalTasks int64
	faults     int64

	startTime     time.Time
	ticks         int64
	throughput    int64
	lastHeartbeat string
}

var sim = &deviceState{
	families:  map[string]*familyState{},
	startTime: time.Now(),
}

// recordCommand bumps the counters for a successfully simulated command (caller has already
// validated the family and command).
func (s *deviceState) recordCommand(family, command string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fs := s.families[family]
	if fs == nil {
		fs = &familyState{commandCounts: map[string]int64{}}
		s.families[family] = fs
	}
	fs.commandCounts[command]++
	fs.totalTasks++
	fs.lastCommand = command
	s.totalTasks++
}

// tick advances the telemetry snapshot once per telemetryLoop iteration; every 7th tick bumps a
// simulated fault so the fault counter is exercised. Returns post-tick ticks/throughput/faults.
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
	families := make(map[string]interface{}, len(s.families))
	for fam, fs := range s.families {
		counts := make(map[string]int64, len(fs.commandCounts))
		for k, v := range fs.commandCounts {
			counts[k] = v
		}
		families[fam] = map[string]interface{}{
			"totalTasks":    fs.totalTasks,
			"lastCommand":   fs.lastCommand,
			"commandCounts": counts,
		}
	}
	return map[string]interface{}{
		"service":  serviceName,
		"emulator": "ON",
		"devices": map[string]interface{}{
			"totalTasks": s.totalTasks,
			"faults":     s.faults,
			"families":   families,
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
