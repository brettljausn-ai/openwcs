package main

import (
	"encoding/json"
	"net/http"
)

// asrs.go adds simulated ASRS crane positions to the hardware twin (alongside the AMR fleet and
// AutoStore grid in twin.go). A small fixed set of cranes, each pinned to an aisle with a world XZ
// position and a lift height y (metres), so the twin can animate a crane travelling the aisle and
// lifting during a STORE/RETRIEVE/RELOCATE. It is read through GET /asrs/cranes and updated as a
// side effect of ASRS-family task processing (a task makes the relevant crane MOVING with its HU
// for the task's duration, then it idles again, dropping the HU). State is simulated only — no real
// hardware — and is guarded by the same twinState mutex as the AMR fleet / AutoStore grid.

// ASRS crane status values reported on the twin (same enum as the AMR fleet).
const (
	craneIdle    = "IDLE"
	craneMoving  = "MOVING"
	craneFaulted = "FAULTED"
)

// asrsCraneCount is the fixed small set of simulated cranes (one per aisle). Kept small and bounded.
const asrsCraneCount = 3

// asrsAislePitch is the X spacing (metres) between aisles, asrsLiftMax the rack's lift height.
const (
	asrsAislePitch = 4.0
	asrsLiftMax    = 8.0
)

// asrsCrane is one simulated ASRS storage-and-retrieval crane: it lives in a fixed aisle, has a
// world XZ position (x = travel along the aisle, z = the aisle's lane) plus a lift height y, a
// status, and the HU code it is carrying while MOVING ("" when empty/idle).
type asrsCrane struct {
	id     string
	aisle  int
	x      float64
	y      float64
	z      float64
	status string
	huCode string // "" = carrying nothing
}

// craneID gives a stable, human-readable identifier (CRANE-01..).
func craneID(i int) string { return "CRANE-" + twoDigit(i+1) }

// seedCranes lays out the fixed crane set at deterministic, plausible world positions: one crane per
// aisle, parked at the rack's near end (x=0) on the ground (y=0), aisles spread along Z.
func seedCranes() []*asrsCrane {
	cranes := make([]*asrsCrane, 0, asrsCraneCount)
	for i := 0; i < asrsCraneCount; i++ {
		cranes = append(cranes, &asrsCrane{
			id:     craneID(i),
			aisle:  i + 1,
			x:      0.0,
			y:      0.0,
			z:      2.0 + float64(i)*asrsAislePitch, // each aisle on its own Z lane
			status: craneIdle,
		})
	}
	return cranes
}

// startASRSTask marks the next crane MOVING, carrying the task's HU, and nudges its position toward a
// plausible target (aisle travel in x + a lift in y, bounded) so the live view shows motion. It
// returns the chosen crane's id so the matching finishASRSTask can release it. Caller does not hold
// the lock. Mirrors startAMRTask in twin.go.
func (t *twinState) startASRSTask(huCode string) string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.cranes) == 0 {
		return ""
	}
	c := t.cranes[t.nextCrane%len(t.cranes)]
	t.nextCrane++
	c.status = craneMoving
	c.huCode = huCode
	// Nudge toward a plausible bay: travel along the aisle (x) and lift (y), kept within bounds so
	// successive tasks stay in the rack rather than drifting off forever.
	c.x += 2.0
	if c.x > asrsAisleEnd {
		c.x = asrsAisleEnd
	}
	c.y += 1.5
	if c.y > asrsLiftMax {
		c.y = asrsLiftMax
	}
	return c.id
}

// asrsAisleEnd is the far end of the aisle a crane may travel to (metres).
const asrsAisleEnd = 20.0

// finishASRSTask returns a crane to IDLE, drops its HU, and lowers it to the ground once its task
// completes/fails. Mirrors finishAMRTask in twin.go.
func (t *twinState) finishASRSTask(id string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	for _, c := range t.cranes {
		if c.id == id {
			c.status = craneIdle
			c.huCode = ""
			c.y = 0.0 // HU dropped, crane lowered back to the ground
			return
		}
	}
}

// asrsCraneView is the GET /asrs/cranes element shape.
type asrsCraneView struct {
	CraneID string  `json:"craneId"`
	Aisle   int     `json:"aisle"`
	X       float64 `json:"x"`
	Y       float64 `json:"y"`
	Z       float64 `json:"z"`
	Busy    bool    `json:"busy"`
	Status  string  `json:"status"`
	HuCode  *string `json:"huCode"`
}

// cranesSnapshot returns the ASRS cranes as a JSON-ready slice read under lock.
func (t *twinState) cranesSnapshot() []asrsCraneView {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := make([]asrsCraneView, 0, len(t.cranes))
	for _, c := range t.cranes {
		out = append(out, asrsCraneView{
			CraneID: c.id,
			Aisle:   c.aisle,
			X:       c.x,
			Y:       c.y,
			Z:       c.z,
			Busy:    c.status == craneMoving,
			Status:  c.status,
			HuCode:  nilIfEmpty(c.huCode),
		})
	}
	return out
}

// handleASRSCranes serves GET /asrs/cranes with the simulated crane positions.
func handleASRSCranes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(twin.cranesSnapshot())
}
