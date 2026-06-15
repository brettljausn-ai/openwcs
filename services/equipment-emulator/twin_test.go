package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// twin_test.go covers the hardware-twin live views (execution-layer epic item 3): GET /amr/fleet,
// GET /autostore/grid, and that AMR-/AUTOSTORE-family task processing reflects in the simulated
// state. Mirrors tasks_test.go/walk_test.go conventions (httptest, package-level handlers).

// getFleet drives GET /amr/fleet through the handler and decodes the fleet JSON.
func getFleet(t *testing.T) []amrFleetView {
	t.Helper()
	rec := httptest.NewRecorder()
	handleAMRFleet(rec, httptest.NewRequest(http.MethodGet, "/amr/fleet", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /amr/fleet status = %d, want 200", rec.Code)
	}
	var fleet []amrFleetView
	if err := json.Unmarshal(rec.Body.Bytes(), &fleet); err != nil {
		t.Fatalf("decode fleet: %v", err)
	}
	return fleet
}

// getGrid drives GET /autostore/grid through the handler and decodes the grid JSON.
func getGrid(t *testing.T) autoStoreGridView {
	t.Helper()
	rec := httptest.NewRecorder()
	handleAutoStoreGrid(rec, httptest.NewRequest(http.MethodGet, "/autostore/grid", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /autostore/grid status = %d, want 200", rec.Code)
	}
	var grid autoStoreGridView
	if err := json.Unmarshal(rec.Body.Bytes(), &grid); err != nil {
		t.Fatalf("decode grid: %v", err)
	}
	return grid
}

// GET /amr/fleet returns the fixed fleet with valid fields.
func TestAMRFleetReturnsFleet(t *testing.T) {
	fleet := getFleet(t)
	if len(fleet) != amrFleetSize {
		t.Fatalf("fleet size = %d, want %d", len(fleet), amrFleetSize)
	}
	for _, r := range fleet {
		if r.AmrID == "" {
			t.Fatalf("robot has empty amrId: %+v", r)
		}
		switch r.Status {
		case amrIdle, amrMoving, amrFaulted:
		default:
			t.Fatalf("robot %s has invalid status %q", r.AmrID, r.Status)
		}
	}
}

// GET /autostore/grid returns the grid shape: columns/rows/totalBins consistent, ports present.
func TestAutoStoreGridShape(t *testing.T) {
	grid := getGrid(t)
	if grid.Columns != autoStoreColumns || grid.Rows != autoStoreRows {
		t.Fatalf("grid dims = %dx%d, want %dx%d", grid.Columns, grid.Rows, autoStoreColumns, autoStoreRows)
	}
	if grid.TotalBins != grid.Columns*grid.Rows {
		t.Fatalf("totalBins = %d, want %d", grid.TotalBins, grid.Columns*grid.Rows)
	}
	if grid.OccupiedBins < 0 || grid.OccupiedBins > grid.TotalBins {
		t.Fatalf("occupiedBins = %d, out of [0,%d]", grid.OccupiedBins, grid.TotalBins)
	}
	if len(grid.Ports) != autoStorePortCount {
		t.Fatalf("ports = %d, want %d", len(grid.Ports), autoStorePortCount)
	}
	for _, p := range grid.Ports {
		if p.PortID == "" {
			t.Fatalf("port has empty portId: %+v", p)
		}
	}
}

// Processing an AMR task marks a robot MOVING carrying the task's HU code (observed at the moment
// the twin is updated by startAMRTask, the same call runTask makes).
func TestAMRTaskMarksRobotMoving(t *testing.T) {
	saved := twin
	twin = newTwinState()
	defer func() { twin = saved }()

	id := twin.startAMRTask("HU-AMR-1")
	if id == "" {
		t.Fatal("startAMRTask returned no robot id")
	}
	found := false
	for _, r := range getFleet(t) {
		if r.AmrID != id {
			continue
		}
		found = true
		if r.Status != amrMoving {
			t.Fatalf("robot %s status = %q, want MOVING", id, r.Status)
		}
		if r.HuCode == nil || *r.HuCode != "HU-AMR-1" {
			t.Fatalf("robot %s huCode = %v, want HU-AMR-1", id, r.HuCode)
		}
	}
	if !found {
		t.Fatalf("robot %s not in fleet", id)
	}

	// After the task finishes the robot idles and drops its HU.
	twin.finishAMRTask(id)
	for _, r := range getFleet(t) {
		if r.AmrID == id {
			if r.Status != amrIdle || r.HuCode != nil {
				t.Fatalf("after finish robot %s = %q huCode=%v, want IDLE/nil", id, r.Status, r.HuCode)
			}
		}
	}
}

// A full AMR task (synchronous path) processes to COMPLETED and the robot ends up IDLE again.
func TestAMRTaskCompletesAndReleasesRobot(t *testing.T) {
	saved := twin
	twin = newTwinState()
	defer func() { twin = saved }()

	res := postTask(t, deviceTaskRequest{
		TaskID: "amr-1", Family: "AMR", Command: "TRANSPORT", EquipmentID: "amr-e1",
		Payload: map[string]interface{}{"huCode": "HU-AMR-2"},
	})
	if res.Status != "COMPLETED" {
		t.Fatalf("status = %q, want COMPLETED", res.Status)
	}
	for _, r := range getFleet(t) {
		if r.Status == amrMoving {
			t.Fatalf("robot %s still MOVING after sync task finished", r.AmrID)
		}
	}
}

// Processing an AUTOSTORE task marks a port busy with the task's HU code.
func TestAutoStoreTaskMarksPortBusy(t *testing.T) {
	saved := twin
	twin = newTwinState()
	defer func() { twin = saved }()

	id := twin.startAutoStoreTask("BIN_RETRIEVE", "HU-AS-1")
	if id == "" {
		t.Fatal("startAutoStoreTask returned no port id")
	}
	grid := getGrid(t)
	var found *autoStorePortView
	for i := range grid.Ports {
		if grid.Ports[i].PortID == id {
			found = &grid.Ports[i]
			break
		}
	}
	if found == nil {
		t.Fatalf("port %s not in grid", id)
	}
	if !found.Busy {
		t.Fatalf("port %s busy = false, want true", id)
	}
	if found.HuCode == nil || *found.HuCode != "HU-AS-1" {
		t.Fatalf("port %s huCode = %v, want HU-AS-1", id, found.HuCode)
	}

	twin.finishAutoStoreTask(id)
	for _, p := range getGrid(t).Ports {
		if p.PortID == id && (p.Busy || p.HuCode != nil) {
			t.Fatalf("after finish port %s busy=%v huCode=%v, want free", id, p.Busy, p.HuCode)
		}
	}
}

// BIN_STORE fills a bin, BIN_RETRIEVE frees one (bounded), reflected in occupiedBins.
func TestAutoStoreBinCountsMove(t *testing.T) {
	saved := twin
	twin = newTwinState()
	defer func() { twin = saved }()

	before := getGrid(t).OccupiedBins
	twin.startAutoStoreTask("BIN_STORE", "HU-AS-store")
	if got := getGrid(t).OccupiedBins; got != before+1 {
		t.Fatalf("after BIN_STORE occupiedBins = %d, want %d", got, before+1)
	}
	twin.startAutoStoreTask("BIN_RETRIEVE", "HU-AS-ret")
	if got := getGrid(t).OccupiedBins; got != before {
		t.Fatalf("after BIN_RETRIEVE occupiedBins = %d, want %d", got, before)
	}
}

// Both twin endpoints are 405 on a non-GET method.
func TestTwinEndpointsRejectNonGet(t *testing.T) {
	for _, h := range []http.HandlerFunc{handleAMRFleet, handleAutoStoreGrid} {
		rec := httptest.NewRecorder()
		h(rec, httptest.NewRequest(http.MethodPost, "/x", nil))
		if rec.Code != http.StatusMethodNotAllowed {
			t.Fatalf("non-GET status = %d, want 405", rec.Code)
		}
	}
}
