package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
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

func TestHandleTaskCompletedWhenEmulatorOn(t *testing.T) {
	setEmulator(true)
	defer setEmulator(false)

	res := postTask(t, deviceTaskRequest{TaskID: "t1", Command: "TRANSPORT", EquipmentID: "robot-1"})
	if res.Status != "COMPLETED" {
		t.Fatalf("status = %q, want COMPLETED", res.Status)
	}
	if res.ResultPayload["simulated"] != true {
		t.Fatalf("resultPayload.simulated = %v, want true", res.ResultPayload["simulated"])
	}
}

func TestHandleTaskFailedWhenEmulatorOff(t *testing.T) {
	setEmulator(false)

	res := postTask(t, deviceTaskRequest{TaskID: "t2", Command: "MOVE"})
	if res.Status != "FAILED" {
		t.Fatalf("status = %q, want FAILED", res.Status)
	}
}

func TestHandleTaskUnsupportedCommand(t *testing.T) {
	setEmulator(true)
	defer setEmulator(false)

	res := postTask(t, deviceTaskRequest{TaskID: "t3", Command: "TELEPORT"})
	if res.Status != "FAILED" {
		t.Fatalf("status = %q, want FAILED", res.Status)
	}
}

func TestHandleTaskRejectsGet(t *testing.T) {
	rec := httptest.NewRecorder()
	handleTask(rec, httptest.NewRequest(http.MethodGet, "/tasks", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}
