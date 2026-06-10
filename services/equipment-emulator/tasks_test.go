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

// TestMain forces zero simulated latency and no fault injection so the suite is fast and predictable.
func TestMain(m *testing.M) {
	latencyOverrideMs.Store(0)
	faultEvery.Store(0)
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
	latencyOverrideMs.Store(25)
	defer latencyOverrideMs.Store(0)

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

// With faultEvery=2, deterministically every 2nd task fails as a simulated equipment fault.
func TestFaultInjectionFailsEveryNth(t *testing.T) {
	taskSeq.Store(0)
	faultEvery.Store(2)
	defer func() { faultEvery.Store(0); taskSeq.Store(0) }()

	wants := []string{"COMPLETED", "FAILED", "COMPLETED", "FAILED"}
	for i, want := range wants {
		res := postTask(t, deviceTaskRequest{TaskID: "t", Family: "ASRS", Command: "RETRIEVE"})
		if res.Status != want {
			t.Fatalf("task %d: status = %q, want %q", i+1, res.Status, want)
		}
		if want == "FAILED" && res.ResultPayload["fault"] != true {
			t.Fatalf("task %d: expected fault=true in payload, got %v", i+1, res.ResultPayload["fault"])
		}
	}
}

// POST /config updates the live latency/fault config; GET /config reports it.
func TestConfigEndpoint(t *testing.T) {
	defer func() { latencyOverrideMs.Store(0); faultEvery.Store(0) }()

	body, _ := json.Marshal(map[string]int64{"latencyOverrideMs": 0, "faultEvery": 5})
	rec := httptest.NewRecorder()
	handleConfig(rec, httptest.NewRequest(http.MethodPost, "/config", bytes.NewReader(body)))
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /config status = %d, want 200", rec.Code)
	}
	if faultEvery.Load() != 5 {
		t.Fatalf("faultEvery = %d, want 5 after POST", faultEvery.Load())
	}

	rec = httptest.NewRecorder()
	handleConfig(rec, httptest.NewRequest(http.MethodGet, "/config", nil))
	var cfg configView
	if err := json.Unmarshal(rec.Body.Bytes(), &cfg); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if cfg.FaultEvery != 5 {
		t.Fatalf("GET /config faultEvery = %d, want 5", cfg.FaultEvery)
	}
}

// With a callbackUrl, the task is acked ACCEPTED immediately and the terminal result is POSTed back.
func TestAsyncDispatchCallsBack(t *testing.T) {
	got := make(chan deviceTaskResult, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var res deviceTaskResult
		_ = json.NewDecoder(r.Body).Decode(&res)
		got <- res
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	res := postTask(t, deviceTaskRequest{TaskID: "t-async", Family: "ASRS", Command: "RETRIEVE", CallbackURL: srv.URL})
	if res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}

	select {
	case cb := <-got:
		if cb.Status != "COMPLETED" {
			t.Fatalf("callback status = %q, want COMPLETED", cb.Status)
		}
		if cb.ResultPayload["family"] != "ASRS" {
			t.Fatalf("callback resultPayload.family = %v, want ASRS", cb.ResultPayload["family"])
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no callback received within 2s")
	}
}
