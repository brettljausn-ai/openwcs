package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"
)

// TestMain forces zero simulated latency so the suite doesn't sleep on every task.
func TestMain(m *testing.M) {
	latencyOverrideMs = 0
	os.Exit(m.Run())
}

func postTask(t *testing.T, req deviceTaskRequest) deviceTaskResult {
	t.Helper()
	body, _ := json.Marshal(req)
	rec := httptest.NewRecorder()
	handleTask(rec, httptest.NewRequest(http.MethodPost, "/tasks", bytes.NewReader(body)))
	var res deviceTaskResult
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return res
}

func TestSimulatesEachFamily(t *testing.T) {
	cases := []struct {
		family  string
		command string
	}{
		{"ASRS", "RETRIEVE"},
		{"CONVEYOR", "CONVEY"},
		{"AMR", "TRANSPORT"},
		{"AUTOSTORE", "BIN_RETRIEVE"},
	}
	for _, c := range cases {
		res := postTask(t, deviceTaskRequest{TaskID: "t", Family: c.family, Command: c.command, EquipmentID: "e1"})
		if res.Status != "COMPLETED" {
			t.Fatalf("%s/%s: status = %q, want COMPLETED", c.family, c.command, res.Status)
		}
		if res.ResultPayload["family"] != c.family {
			t.Fatalf("%s/%s: resultPayload.family = %v, want %s", c.family, c.command, res.ResultPayload["family"], c.family)
		}
	}
}

func TestFamilyCaseInsensitive(t *testing.T) {
	res := postTask(t, deviceTaskRequest{TaskID: "t", Family: "autostore", Command: "BIN_STORE"})
	if res.Status != "COMPLETED" {
		t.Fatalf("status = %q, want COMPLETED", res.Status)
	}
}

func TestUnknownFamilyFails(t *testing.T) {
	res := postTask(t, deviceTaskRequest{TaskID: "t", Family: "TARDIS", Command: "SCAN"})
	if res.Status != "FAILED" {
		t.Fatalf("status = %q, want FAILED", res.Status)
	}
}

func TestUnsupportedCommandForFamilyFails(t *testing.T) {
	// RETRIEVE is an ASRS command, not a CONVEYOR one.
	res := postTask(t, deviceTaskRequest{TaskID: "t", Family: "CONVEYOR", Command: "RETRIEVE"})
	if res.Status != "FAILED" {
		t.Fatalf("status = %q, want FAILED", res.Status)
	}
}

func TestRejectsGet(t *testing.T) {
	rec := httptest.NewRecorder()
	handleTask(rec, httptest.NewRequest(http.MethodGet, "/tasks", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}

// The handler sleeps for the simulated latency and reports it as durationMs.
func TestReportsSimulatedDuration(t *testing.T) {
	latencyOverrideMs = 25
	defer func() { latencyOverrideMs = 0 }()

	start := time.Now()
	res := postTask(t, deviceTaskRequest{TaskID: "t", Family: "ASRS", Command: "RETRIEVE"})
	elapsed := time.Since(start)

	if res.Status != "COMPLETED" {
		t.Fatalf("status = %q, want COMPLETED", res.Status)
	}
	if res.ResultPayload["durationMs"] != float64(25) {
		t.Fatalf("durationMs = %v, want 25", res.ResultPayload["durationMs"])
	}
	if elapsed < 20*time.Millisecond {
		t.Fatalf("handler returned in %s; expected it to honour the ~25ms simulated latency", elapsed)
	}
}
