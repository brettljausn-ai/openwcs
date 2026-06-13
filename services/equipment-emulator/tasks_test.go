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

// TestMain forces zero simulated latency, no fault injection, and no recirculation so the suite is
// fast and predictable.
func TestMain(m *testing.M) {
	latencyOverrideMs.Store(0)
	faultEvery.Store(0)
	recircEvery.Store(0)
	asrsHandoverMs.Store(0) // no handover spacing in the task suite — keep it fast and deterministic
	conveySeq.Store(0)
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
		{"ASRS", "RELOCATE"},
		{"CONVEYOR", "CONVEY"},
		{"AMR", "TRANSPORT"},
		{"AUTOSTORE", "BIN_RETRIEVE"},
		{"AUTOSTORE", "BIN_RELOCATE"},
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

// The CONVEY leg (ADR-0007 §4.2/§4.3): flow dispatches a CONVEYOR/CONVEY device task with a
// callbackUrl; the emulator runs it as the conveyor leg and POSTs COMPLETED back via the async §3b
// callback. That COMPLETED callback IS the arrival event flow keys onConveyCompleted off. This test
// locks that the conveyor leg is acked ACCEPTED and then completes asynchronously.
func TestConveyLegCompletesViaAsyncCallback(t *testing.T) {
	got := make(chan deviceTaskResult, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var res deviceTaskResult
		_ = json.NewDecoder(r.Body).Decode(&res)
		got <- res
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	res := postTask(t, deviceTaskRequest{
		TaskID:  "convey-1",
		Family:  "CONVEYOR",
		Command: "CONVEY",
		Payload: map[string]interface{}{
			"destinationWorkplaceId": "wp-1",
			"huId":                   "hu-1",
		},
		CallbackURL: srv.URL,
	})
	if res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}

	select {
	case cb := <-got:
		if cb.Status != "COMPLETED" {
			t.Fatalf("CONVEY callback status = %q, want COMPLETED (= arrival)", cb.Status)
		}
		if cb.ResultPayload["family"] != "CONVEYOR" {
			t.Fatalf("callback resultPayload.family = %v, want CONVEYOR", cb.ResultPayload["family"])
		}
		if cb.ResultPayload["command"] != "CONVEY" {
			t.Fatalf("callback resultPayload.command = %v, want CONVEY", cb.ResultPayload["command"])
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no CONVEY callback received within 2s")
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

// A RELOCATE (ADR-0009 dig-out shuttle move) is acked ACCEPTED, honours the simulated shuttle-move
// latency, and completes via the async result callback like every other command.
func TestRelocateCompletesWithLatencyViaCallback(t *testing.T) {
	latencyOverrideMs.Store(25)
	defer latencyOverrideMs.Store(0)

	got := make(chan deviceTaskResult, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var res deviceTaskResult
		_ = json.NewDecoder(r.Body).Decode(&res)
		got <- res
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	start := time.Now()
	res := postTask(t, deviceTaskRequest{
		TaskID:  "relocate-1",
		Family:  "ASRS",
		Command: "RELOCATE",
		Payload: map[string]interface{}{
			"huId":           "hu-blocker",
			"fromLocationId": "loc-deep-front",
			"toLocationId":   "loc-same-level",
			"forHuId":        "hu-target",
		},
		CallbackURL: srv.URL,
	})
	if res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}

	select {
	case cb := <-got:
		if cb.Status != "COMPLETED" {
			t.Fatalf("RELOCATE callback status = %q, want COMPLETED", cb.Status)
		}
		if cb.ResultPayload["command"] != "RELOCATE" {
			t.Fatalf("callback resultPayload.command = %v, want RELOCATE", cb.ResultPayload["command"])
		}
		if cb.ResultPayload["family"] != "ASRS" {
			t.Fatalf("callback resultPayload.family = %v, want ASRS", cb.ResultPayload["family"])
		}
		if cb.ResultPayload["durationMs"] != float64(25) {
			t.Fatalf("durationMs = %v, want 25", cb.ResultPayload["durationMs"])
		}
		if elapsed := time.Since(start); elapsed < 20*time.Millisecond {
			t.Fatalf("callback after %s; expected it to honour the ~25ms simulated latency", elapsed)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no RELOCATE callback received within 2s")
	}
}

// The dig-out shuttle move has its own (slow) default latency entry, override-able like the others.
func TestRelocateDefaultLatencyIsShuttleMove(t *testing.T) {
	latencyOverrideMs.Store(-1) // use per-command defaults
	defer latencyOverrideMs.Store(0)

	if d := commandLatency("ASRS", "RELOCATE"); d != 3000*time.Millisecond {
		t.Fatalf("ASRS/RELOCATE default latency = %s, want 3s", d)
	}
	if d := commandLatency("AUTOSTORE", "BIN_RELOCATE"); d != 3000*time.Millisecond {
		t.Fatalf("AUTOSTORE/BIN_RELOCATE default latency = %s, want 3s", d)
	}
}

// conveyJourney deterministically recirculates every Nth CONVEY, adding loop time and a RECIRCULATED
// decision before the final DIVERTED — so arrival order can diverge from dispatch order (R2).
func TestConveyJourneyRecirculatesDeterministically(t *testing.T) {
	recircEvery.Store(2)
	conveySeq.Store(0)
	defer func() { recircEvery.Store(0); conveySeq.Store(0) }()

	extra1, dec1 := conveyJourney() // CONVEY #1: 1%2 != 0 -> no recirc
	if extra1 != 0 || len(dec1) != 1 || dec1[0]["event"] != "DIVERTED" {
		t.Fatalf("pass1: extra=%v decisions=%v, want 0 extra and only DIVERTED", extra1, dec1)
	}
	extra2, dec2 := conveyJourney() // CONVEY #2: 2%2 == 0 -> recirculate once
	if extra2 != loopLatency || len(dec2) != 2 || dec2[0]["event"] != "RECIRCULATED" || dec2[1]["event"] != "DIVERTED" {
		t.Fatalf("pass2: extra=%v decisions=%v, want loopLatency and [RECIRCULATED, DIVERTED]", extra2, dec2)
	}
}

// A recirculating CONVEY task reports its recirculations + decision points in the result payload.
func TestConveyTaskReportsDecisions(t *testing.T) {
	recircEvery.Store(1) // every CONVEY recirculates once
	conveySeq.Store(0)
	saved := loopLatency
	loopLatency = 2 * time.Millisecond // keep the test fast
	defer func() { recircEvery.Store(0); conveySeq.Store(0); loopLatency = saved }()

	res := postTask(t, deviceTaskRequest{TaskID: "c1", Family: "CONVEYOR", Command: "CONVEY", EquipmentID: "belt-1"})
	if res.Status != "COMPLETED" {
		t.Fatalf("status = %q, want COMPLETED", res.Status)
	}
	if res.ResultPayload["recirculations"] != float64(1) {
		t.Fatalf("recirculations = %v, want 1", res.ResultPayload["recirculations"])
	}
	decs, ok := res.ResultPayload["decisions"].([]interface{})
	if !ok || len(decs) != 2 {
		t.Fatalf("decisions = %v, want 2 entries (RECIRCULATED, DIVERTED)", res.ResultPayload["decisions"])
	}
}
