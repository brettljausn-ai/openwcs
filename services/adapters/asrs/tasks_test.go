package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

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

// With emulation moved to the equipment-emulator service, this real adapter has no live hardware
// path: a supported command fails cleanly as "not connected".
func TestSupportedCommandFailsNotConnected(t *testing.T) {
	res := postTask(t, deviceTaskRequest{TaskID: "t1", Command: "STORE", EquipmentID: "crane-1"})
	if res.Status != "FAILED" {
		t.Fatalf("status = %q, want FAILED", res.Status)
	}
	if !strings.Contains(res.Detail, "not connected") {
		t.Fatalf("detail = %q, want it to mention 'not connected'", res.Detail)
	}
}

func TestUnsupportedCommandFails(t *testing.T) {
	res := postTask(t, deviceTaskRequest{TaskID: "t2", Command: "TELEPORT"})
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
