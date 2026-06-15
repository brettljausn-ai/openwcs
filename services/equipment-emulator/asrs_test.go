package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// asrs_test.go covers the hardware-twin ASRS crane view: GET /asrs/cranes and that ASRS-family task
// processing reflects in the simulated crane state. Mirrors twin_test.go conventions (httptest,
// package-level handlers, swap the package twin around each test).

// getCranes drives GET /asrs/cranes through the handler and decodes the crane JSON.
func getCranes(t *testing.T) []asrsCraneView {
	t.Helper()
	rec := httptest.NewRecorder()
	handleASRSCranes(rec, httptest.NewRequest(http.MethodGet, "/asrs/cranes", nil))
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /asrs/cranes status = %d, want 200", rec.Code)
	}
	var cranes []asrsCraneView
	if err := json.Unmarshal(rec.Body.Bytes(), &cranes); err != nil {
		t.Fatalf("decode cranes: %v", err)
	}
	return cranes
}

// GET /asrs/cranes returns the fixed crane set with valid fields + a valid status enum.
func TestASRSCranesReturnsCranes(t *testing.T) {
	cranes := getCranes(t)
	if len(cranes) != asrsCraneCount {
		t.Fatalf("crane count = %d, want %d", len(cranes), asrsCraneCount)
	}
	for _, c := range cranes {
		if c.CraneID == "" {
			t.Fatalf("crane has empty craneId: %+v", c)
		}
		if c.Aisle < 1 {
			t.Fatalf("crane %s has invalid aisle %d", c.CraneID, c.Aisle)
		}
		if c.Y < 0 || c.Y > asrsLiftMax {
			t.Fatalf("crane %s lift y = %g, out of [0,%g]", c.CraneID, c.Y, asrsLiftMax)
		}
		switch c.Status {
		case craneIdle, craneMoving, craneFaulted:
		default:
			t.Fatalf("crane %s has invalid status %q", c.CraneID, c.Status)
		}
		// busy must agree with the MOVING status.
		if c.Busy != (c.Status == craneMoving) {
			t.Fatalf("crane %s busy=%v but status=%q", c.CraneID, c.Busy, c.Status)
		}
	}
}

// Processing an ASRS task marks a crane MOVING carrying the task's HU code, lifted off the ground
// and travelled along its aisle (observed at the moment startASRSTask updates the twin, the same
// call runTask makes). Tested directly since zero-latency tasks release the crane immediately.
func TestASRSTaskMarksCraneMoving(t *testing.T) {
	saved := twin
	twin = newTwinState()
	defer func() { twin = saved }()

	id := twin.startASRSTask("HU-ASRS-1")
	if id == "" {
		t.Fatal("startASRSTask returned no crane id")
	}
	found := false
	for _, c := range getCranes(t) {
		if c.CraneID != id {
			continue
		}
		found = true
		if c.Status != craneMoving {
			t.Fatalf("crane %s status = %q, want MOVING", id, c.Status)
		}
		if !c.Busy {
			t.Fatalf("crane %s busy = false, want true while MOVING", id)
		}
		if c.HuCode == nil || *c.HuCode != "HU-ASRS-1" {
			t.Fatalf("crane %s huCode = %v, want HU-ASRS-1", id, c.HuCode)
		}
		if c.X <= 0 || c.Y <= 0 {
			t.Fatalf("crane %s did not move toward a bay: x=%g y=%g", id, c.X, c.Y)
		}
		if c.X > asrsAisleEnd || c.Y > asrsLiftMax {
			t.Fatalf("crane %s drifted out of bounds: x=%g y=%g", id, c.X, c.Y)
		}
	}
	if !found {
		t.Fatalf("crane %s not in crane set", id)
	}

	// After the task finishes the crane idles, drops its HU, and lowers to the ground.
	twin.finishASRSTask(id)
	for _, c := range getCranes(t) {
		if c.CraneID == id {
			if c.Status != craneIdle || c.HuCode != nil || c.Busy || c.Y != 0 {
				t.Fatalf("after finish crane %s = %q huCode=%v busy=%v y=%g, want IDLE/nil/false/0",
					id, c.Status, c.HuCode, c.Busy, c.Y)
			}
		}
	}
}

// A full ASRS task (synchronous path) processes to COMPLETED and the crane ends up IDLE again.
func TestASRSTaskCompletesAndReleasesCrane(t *testing.T) {
	saved := twin
	twin = newTwinState()
	defer func() { twin = saved }()

	res := postTask(t, deviceTaskRequest{
		TaskID: "asrs-1", Family: "ASRS", Command: "RETRIEVE", EquipmentID: "asrs-e1",
		Payload: map[string]interface{}{"huCode": "HU-ASRS-2"},
	})
	if res.Status != "COMPLETED" {
		t.Fatalf("status = %q, want COMPLETED", res.Status)
	}
	for _, c := range getCranes(t) {
		if c.Status == craneMoving {
			t.Fatalf("crane %s still MOVING after sync task finished", c.CraneID)
		}
	}
}

// GET /asrs/cranes is 405 on a non-GET method.
func TestASRSCranesRejectNonGet(t *testing.T) {
	rec := httptest.NewRecorder()
	handleASRSCranes(rec, httptest.NewRequest(http.MethodPost, "/asrs/cranes", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("non-GET status = %d, want 405", rec.Code)
	}
}
