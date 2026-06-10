package main

import (
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

// state.go holds the in-memory simulated state and telemetry for all families, derived from real
// task load: per-family completed/failed tallies plus a liveness heartbeat. Guarded by one mutex.

// familyState is one family's running tallies.
type familyState struct {
	commandCounts map[string]int64 // successful commands, by command
	completed     int64
	failed        int64
	lastCommand   string
}

// deviceState is the emulator's whole-fleet state: per-family tallies plus a telemetry snapshot.
type deviceState struct {
	mu sync.Mutex

	families       map[string]*familyState
	totalCompleted int64
	totalFailed    int64

	startTime     time.Time
	ticks         int64
	lastHeartbeat string
}

var sim = &deviceState{
	families:  map[string]*familyState{},
	startTime: time.Now(),
}

// family returns (creating if needed) the per-family tallies. Caller holds the lock.
func (s *deviceState) family(family string) *familyState {
	fs := s.families[family]
	if fs == nil {
		fs = &familyState{commandCounts: map[string]int64{}}
		s.families[family] = fs
	}
	return fs
}

// recordCompleted bumps the success tallies for a simulated command.
func (s *deviceState) recordCompleted(family, command string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fs := s.family(family)
	fs.commandCounts[command]++
	fs.completed++
	fs.lastCommand = command
	s.totalCompleted++
}

// recordFailed bumps the fault tallies for a simulated equipment fault.
func (s *deviceState) recordFailed(family, command string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	fs := s.family(family)
	fs.failed++
	fs.lastCommand = command
	s.totalFailed++
}

// tick advances the liveness heartbeat once per telemetryLoop iteration and returns the running
// totals so the caller can log a heartbeat without re-taking the lock.
func (s *deviceState) tick(now time.Time) (ticks, completed, failed int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.ticks++
	s.lastHeartbeat = now.Format(time.RFC3339)
	return s.ticks, s.totalCompleted, s.totalFailed
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
			"completed":     fs.completed,
			"failed":        fs.failed,
			"lastCommand":   fs.lastCommand,
			"commandCounts": counts,
		}
	}
	return map[string]interface{}{
		"service":  serviceName,
		"emulator": "ON",
		"config": map[string]interface{}{
			"latencyOverrideMs": latencyOverrideMs.Load(),
			"faultEvery":        faultEvery.Load(),
		},
		"devices": map[string]interface{}{
			"completed": s.totalCompleted,
			"failed":    s.totalFailed,
			"total":     s.totalCompleted + s.totalFailed,
			"families":  families,
		},
		"telemetry": map[string]interface{}{
			"ticks":         s.ticks,
			"uptimeSeconds": int64(time.Since(s.startTime).Seconds()),
			"lastHeartbeat": s.lastHeartbeat,
		},
	}
}

// handleState serves GET /state with the in-memory device + telemetry snapshot.
func handleState(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(sim.snapshot())
}
