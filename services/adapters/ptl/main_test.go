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

// state returns the current /state lit set decoded back into litLight values.
func state(t *testing.T) map[string]litLight {
	t.Helper()
	rec := httptest.NewRecorder()
	handleState(rec, httptest.NewRequest(http.MethodGet, "/state", nil))
	var body struct {
		Lit map[string]litLight `json:"lit"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode state: %v", err)
	}
	return body.Lit
}

// The happy path: ILLUMINATE lights a light (reflected in /state), CLEAR turns it off (gone from
// /state). Table-driven over the device contract commands plus the failure cases.
func TestTaskHandling(t *testing.T) {
	cases := []struct {
		name       string
		command    string
		payload    map[string]interface{}
		wantStatus string
		wantDetail string
	}{
		{"illuminate", "ILLUMINATE", map[string]interface{}{"lightId": "A-01", "qty": 3, "color": "green"}, "COMPLETED", ""},
		{"clear", "CLEAR", map[string]interface{}{"lightId": "A-01"}, "COMPLETED", ""},
		{"unsupported command", "BLINK", map[string]interface{}{"lightId": "A-01"}, "FAILED", "unsupported command"},
		{"missing lightId", "ILLUMINATE", map[string]interface{}{"qty": 1}, "FAILED", "missing payload.lightId"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			res := postTask(t, deviceTaskRequest{TaskID: "t", Family: "PTL", Command: c.command, Payload: c.payload})
			if res.Status != c.wantStatus {
				t.Fatalf("status = %q, want %q", res.Status, c.wantStatus)
			}
			if c.wantDetail != "" && !strings.Contains(res.Detail, c.wantDetail) {
				t.Fatalf("detail = %q, want it to contain %q", res.Detail, c.wantDetail)
			}
		})
	}
}

// End-to-end through /state: ILLUMINATE then CLEAR, observing the lit set each step.
func TestIlluminateThenClearReflectedInState(t *testing.T) {
	if res := postTask(t, deviceTaskRequest{
		TaskID: "t1", Family: "PTL", Command: "ILLUMINATE",
		Payload: map[string]interface{}{"lightId": "B-07", "qty": 5, "color": "amber"},
	}); res.Status != "COMPLETED" {
		t.Fatalf("ILLUMINATE status = %q, want COMPLETED", res.Status)
	}

	lit := state(t)
	got, ok := lit["B-07"]
	if !ok {
		t.Fatalf("light B-07 not in /state after ILLUMINATE: %v", lit)
	}
	if got.Qty != 5 || got.Color != "amber" {
		t.Fatalf("light B-07 = %+v, want qty=5 color=amber", got)
	}

	if res := postTask(t, deviceTaskRequest{
		TaskID: "t2", Family: "PTL", Command: "CLEAR",
		Payload: map[string]interface{}{"lightId": "B-07"},
	}); res.Status != "COMPLETED" {
		t.Fatalf("CLEAR status = %q, want COMPLETED", res.Status)
	}
	if lit := state(t); func() bool { _, ok := lit["B-07"]; return ok }() {
		t.Fatalf("light B-07 still in /state after CLEAR: %v", lit)
	}
}

func TestRejectsGet(t *testing.T) {
	rec := httptest.NewRecorder()
	handleTask(rec, httptest.NewRequest(http.MethodGet, "/tasks", nil))
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want 405", rec.Code)
	}
}
