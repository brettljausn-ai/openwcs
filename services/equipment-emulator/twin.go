package main

import (
	"encoding/json"
	"net/http"
	"sync"
)

// twin.go holds best-effort, in-memory simulated live state for the hardware twin's live views
// (execution-layer epic item 3): a small AMR fleet with world XZ positions and an AutoStore grid
// with fill level + ports. It is read through GET /amr/fleet and GET /autostore/grid, and updated
// as a side effect of AMR-/AUTOSTORE-family task processing (a task makes the relevant robot/port
// busy for the task's duration, then it idles again). State is simulated only — no real hardware is
// involved — and is guarded by its own mutex, mirroring the deviceState pattern in state.go.

// AMR robot status values reported on the twin.
const (
	amrIdle    = "IDLE"
	amrMoving  = "MOVING"
	amrFaulted = "FAULTED"
)

// amrFleetSize is the fixed small simulated fleet the twin shows. Kept small and bounded.
const amrFleetSize = 4

// amrRobot is one simulated AMR: a world XZ position in metres, a status, and the HU code it is
// carrying while MOVING (nil when empty/idle).
type amrRobot struct {
	id     string
	x      float64
	z      float64
	status string
	huCode string // "" = carrying nothing
}

// autoStorePortCount is the fixed number of simulated ports on the grid.
const autoStorePortCount = 3

// autoStore grid geometry: a bounded simulated grid with a fill level that ports work against.
const (
	autoStoreColumns  = 12
	autoStoreRows     = 16
	autoStoreTotalBin = autoStoreColumns * autoStoreRows
)

// autoStorePort is one simulated carousel/port on the grid: a world XZ position, a busy flag, and
// the HU code being presented while busy.
type autoStorePort struct {
	id     string
	x      float64
	z      float64
	busy   bool
	huCode string
}

// twinState is the whole simulated twin view: the AMR fleet plus the AutoStore grid. Its own mutex
// keeps it independent of (and as thread-safe as) the deviceState telemetry in state.go.
type twinState struct {
	mu sync.Mutex

	robots       []*amrRobot
	nextRobot    int // round-robin pointer so successive AMR tasks spread across the fleet
	occupiedBins int // AutoStore bins currently filled (drifts within bounds as tasks run)
	ports        []*autoStorePort
	cranes       []*asrsCrane
	nextCrane    int // round-robin pointer so successive ASRS tasks spread across the cranes
}

// twin is the package-wide simulated twin state, seeded with a deterministic fleet + grid so tests
// and the live view always start from the same plausible layout.
var twin = newTwinState()

// newTwinState lays out the fixed fleet and ports at deterministic, plausible world positions.
func newTwinState() *twinState {
	t := &twinState{occupiedBins: autoStoreTotalBin / 2} // start ~half full
	for i := 0; i < amrFleetSize; i++ {
		t.robots = append(t.robots, &amrRobot{
			id:     amrID(i),
			x:      4.0 + float64(i)*3.0, // spread along X, 3 m apart
			z:      2.0 + float64(i%2)*4.0,
			status: amrIdle,
		})
	}
	for i := 0; i < autoStorePortCount; i++ {
		t.ports = append(t.ports, &autoStorePort{
			id: portID(i),
			x:  float64(autoStoreColumns) + 1.0,    // ports sit just off the grid's +X edge
			z:  2.0 + float64(i)*float64(autoStoreRows)/float64(autoStorePortCount),
		})
	}
	t.cranes = seedCranes()
	return t
}

// amrID / portID give stable, human-readable identifiers (AMR-01.., PORT-01..).
func amrID(i int) string  { return "AMR-" + twoDigit(i+1) }
func portID(i int) string { return "PORT-" + twoDigit(i+1) }

func twoDigit(n int) string {
	if n < 10 {
		return "0" + string(rune('0'+n))
	}
	return string(rune('0'+n/10)) + string(rune('0'+n%10))
}

// huCodeOrEmpty resolves the HU code a task is moving from its payload (the code, falling back to
// the huId), "" when the payload carries neither.
func huCodeOrEmpty(payload map[string]interface{}) string {
	if code := payloadString(payload, "huCode"); code != "" {
		return code
	}
	return payloadString(payload, "huId")
}

// startAMRTask marks the next robot in the fleet MOVING, carrying the task's HU, and nudges its
// world position so the live view shows motion. It returns the chosen robot's id so the matching
// finishAMRTask can release it. Caller does not hold the lock.
func (t *twinState) startAMRTask(huCode string) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.robots) == 0 {
		return ""
	}
	r := t.robots[t.nextRobot%len(t.robots)]
	t.nextRobot++
	r.status = amrMoving
	r.huCode = huCode
	// Nudge the position deterministically so successive views show the robot having moved.
	r.x += 1.0
	r.z += 0.5
	return r.id
}

// finishAMRTask returns a robot to IDLE and drops its HU once its task completes/fails.
func (t *twinState) finishAMRTask(id string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	for _, r := range t.robots {
		if r.id == id {
			r.status = amrIdle
			r.huCode = ""
			return
		}
	}
}

// startAutoStoreTask marks the next free port busy with the task's HU and bumps the occupied-bin
// count for a retrieve-style move (kept within [0,totalBins]). Returns the port id to release.
func (t *twinState) startAutoStoreTask(command, huCode string) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	var port *autoStorePort
	for _, p := range t.ports {
		if !p.busy {
			port = p
			break
		}
	}
	if port == nil && len(t.ports) > 0 {
		port = t.ports[0] // all busy: reuse the first so a task always reflects somewhere
	}
	if port == nil {
		return ""
	}
	port.busy = true
	port.huCode = huCode
	// Reflect the bin movement: a store fills a bin, a retrieve frees one (bounded).
	switch command {
	case "BIN_STORE":
		if t.occupiedBins < autoStoreTotalBin {
			t.occupiedBins++
		}
	case "BIN_RETRIEVE":
		if t.occupiedBins > 0 {
			t.occupiedBins--
		}
	}
	return port.id
}

// finishAutoStoreTask frees a port once its task completes/fails.
func (t *twinState) finishAutoStoreTask(id string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	for _, p := range t.ports {
		if p.id == id {
			p.busy = false
			p.huCode = ""
			return
		}
	}
}

// amrFleetView is the GET /amr/fleet element shape.
type amrFleetView struct {
	AmrID  string  `json:"amrId"`
	X      float64 `json:"x"`
	Z      float64 `json:"z"`
	Status string  `json:"status"`
	HuCode *string `json:"huCode"`
}

// autoStorePortView / autoStoreGridView are the GET /autostore/grid shapes.
type autoStorePortView struct {
	PortID string  `json:"portId"`
	X      float64 `json:"x"`
	Z      float64 `json:"z"`
	Busy   bool    `json:"busy"`
	HuCode *string `json:"huCode"`
}

type autoStoreGridView struct {
	Columns      int                 `json:"columns"`
	Rows         int                 `json:"rows"`
	TotalBins    int                 `json:"totalBins"`
	OccupiedBins int                 `json:"occupiedBins"`
	Ports        []autoStorePortView `json:"ports"`
}

// fleetSnapshot returns the AMR fleet as a JSON-ready slice read under lock.
func (t *twinState) fleetSnapshot() []amrFleetView {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := make([]amrFleetView, 0, len(t.robots))
	for _, r := range t.robots {
		out = append(out, amrFleetView{
			AmrID:  r.id,
			X:      r.x,
			Z:      r.z,
			Status: r.status,
			HuCode: nilIfEmpty(r.huCode),
		})
	}
	return out
}

// gridSnapshot returns the AutoStore grid + ports as a JSON-ready view read under lock.
func (t *twinState) gridSnapshot() autoStoreGridView {
	t.mu.Lock()
	defer t.mu.Unlock()
	ports := make([]autoStorePortView, 0, len(t.ports))
	for _, p := range t.ports {
		ports = append(ports, autoStorePortView{
			PortID: p.id,
			X:      p.x,
			Z:      p.z,
			Busy:   p.busy,
			HuCode: nilIfEmpty(p.huCode),
		})
	}
	return autoStoreGridView{
		Columns:      autoStoreColumns,
		Rows:         autoStoreRows,
		TotalBins:    autoStoreTotalBin,
		OccupiedBins: t.occupiedBins,
		Ports:        ports,
	}
}

// nilIfEmpty maps "" to a JSON null (so huCode is null, not "", when empty).
func nilIfEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// handleAMRFleet serves GET /amr/fleet with the simulated robot fleet.
func handleAMRFleet(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(twin.fleetSnapshot())
}

// handleAutoStoreGrid serves GET /autostore/grid with the simulated grid + ports.
func handleAutoStoreGrid(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(twin.gridSnapshot())
}
