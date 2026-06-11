package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// walk_test.go covers the live conveyor walk (ADR-0008 Phase 3d-2) against a fake flow server:
// a 3-node topology A→B→C (1 m edges) with scripted routing decisions. latencyOverrideMs=0
// (TestMain) makes edge travel and HOLD dwell instantaneous, so the suite stays fast.

// fakeFlow is an httptest server standing in for flow-orchestrator: it serves the conveyor
// topology, answers scans from a script, and captures the task-result callback.
type fakeFlow struct {
	srv      *httptest.Server
	mu       sync.Mutex
	scanned  []string                                    // node codes, in scan order
	decide   func(scan int, node string) routingDecision // scripted answers (scan is 1-based)
	callback chan deviceTaskResult
}

func newFakeFlow(t *testing.T, decide func(scan int, node string) routingDecision) *fakeFlow {
	t.Helper()
	f := &fakeFlow{decide: decide, callback: make(chan deviceTaskResult, 1)}
	mux := http.NewServeMux()
	mux.HandleFunc("/api/flow/conveyor/topology", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("warehouseId") == "" {
			http.Error(w, "missing warehouseId", http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{
			"nodes": [
				{"code": "A", "posX": 0, "posY": 0},
				{"code": "B", "posX": 1, "posY": 0, "loopCode": "L1"},
				{"code": "C", "posX": 2, "posY": 0}
			],
			"edges": [
				{"fromCode": "A", "toCode": "B", "exitCode": "E1", "cost": 1},
				{"fromCode": "B", "toCode": "C", "exitCode": "E2", "cost": 1}
			]
		}`))
	})
	mux.HandleFunc("/api/flow/conveyor/routing-requests", func(w http.ResponseWriter, r *http.Request) {
		var scan struct {
			WarehouseID string `json:"warehouseId"`
			Node        string `json:"node"`
			Barcode     string `json:"barcode"`
		}
		if err := json.NewDecoder(r.Body).Decode(&scan); err != nil {
			http.Error(w, "bad scan", http.StatusBadRequest)
			return
		}
		f.mu.Lock()
		f.scanned = append(f.scanned, scan.Node)
		n := len(f.scanned)
		f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(f.decide(n, scan.Node))
	})
	mux.HandleFunc("/api/flow/device-tasks/", func(w http.ResponseWriter, r *http.Request) {
		var res deviceTaskResult
		_ = json.NewDecoder(r.Body).Decode(&res)
		f.callback <- res
		w.WriteHeader(http.StatusOK)
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeFlow) callbackURL() string {
	return f.srv.URL + "/api/flow/device-tasks/t-walk/result"
}

func (f *fakeFlow) scans() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.scanned...)
}

func (f *fakeFlow) awaitCallback(t *testing.T) deviceTaskResult {
	t.Helper()
	select {
	case res := <-f.callback:
		return res
	case <-time.After(5 * time.Second):
		t.Fatal("no result callback received within 5s")
		return deviceTaskResult{}
	}
}

func walkTask(flow *fakeFlow) deviceTaskRequest {
	return deviceTaskRequest{
		TaskID:      "t-walk",
		WarehouseID: "wh-1",
		Family:      "CONVEYOR",
		Command:     "CONVEY",
		Payload: map[string]interface{}{
			"entryNode": "A",
			"huCode":    "HU-001",
			"huId":      "11111111-1111-1111-1111-111111111111",
		},
		CallbackURL: flow.callbackURL(),
	}
}

// Happy path: A ROUTE→B, B ROUTE→C, C COMPLETE. The walk scans A, B, C in order and the callback
// carries COMPLETED with walked=true and 3 decisions (ROUTED, ROUTED, ARRIVED). recircEvery is
// deliberately on to lock that the internal recirculation simulation does NOT apply to live walks.
func TestLiveWalkScansEveryNodeAndCompletes(t *testing.T) {
	recircEvery.Store(1) // would recirculate every atomic CONVEY — must not touch live walks
	defer func() { recircEvery.Store(0); conveySeq.Store(0) }()

	flow := newFakeFlow(t, func(scan int, node string) routingDecision {
		switch node {
		case "A":
			return routingDecision{Action: "ROUTE", ExitCode: "E1", ToNode: "B"}
		case "B":
			return routingDecision{Action: "ROUTE", ExitCode: "E2", ToNode: "C"}
		default:
			return routingDecision{Action: "COMPLETE", TargetReached: "C"}
		}
	})

	res := postTask(t, walkTask(flow))
	if res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}

	cb := flow.awaitCallback(t)
	if cb.Status != "COMPLETED" {
		t.Fatalf("callback status = %q (detail %q), want COMPLETED", cb.Status, cb.Detail)
	}
	if got, want := flow.scans(), []string{"A", "B", "C"}; strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("scanned nodes = %v, want %v", got, want)
	}
	if cb.ResultPayload["walked"] != true {
		t.Fatalf("walked = %v, want true", cb.ResultPayload["walked"])
	}
	if cb.ResultPayload["scans"] != float64(3) {
		t.Fatalf("scans = %v, want 3", cb.ResultPayload["scans"])
	}
	if cb.ResultPayload["recirculations"] != float64(0) {
		t.Fatalf("recirculations = %v, want 0 (recircEvery must not apply to live walks)", cb.ResultPayload["recirculations"])
	}
	decs, ok := cb.ResultPayload["decisions"].([]interface{})
	if !ok || len(decs) != 3 {
		t.Fatalf("decisions = %v, want 3 entries", cb.ResultPayload["decisions"])
	}
	wantEvents := []string{"ROUTED", "ROUTED", "ARRIVED"}
	for i, d := range decs {
		m := d.(map[string]interface{})
		if m["event"] != wantEvents[i] {
			t.Fatalf("decision %d event = %v, want %s (decisions %v)", i, m["event"], wantEvents[i], decs)
		}
	}
	if last := decs[2].(map[string]interface{}); last["point"] != "C" || last["decision"] != "C" {
		t.Fatalf("ARRIVED decision = %v, want point=C decision=C (targetReached)", last)
	}
}

// HOLD: B answers HOLD once (loop full) then ROUTE. The tote rescans the same node, so the walk
// takes 4 scans and reports recirculations=1.
func TestLiveWalkHoldRescansSameNode(t *testing.T) {
	heldOnce := false
	flow := newFakeFlow(t, func(scan int, node string) routingDecision {
		switch node {
		case "A":
			return routingDecision{Action: "ROUTE", ToNode: "B"}
		case "B":
			if !heldOnce {
				heldOnce = true
				return routingDecision{Action: "HOLD", Detail: "loop L1 full"}
			}
			return routingDecision{Action: "ROUTE", ToNode: "C"}
		default:
			return routingDecision{Action: "COMPLETE", TargetReached: "C"}
		}
	})

	if res := postTask(t, walkTask(flow)); res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}
	cb := flow.awaitCallback(t)
	if cb.Status != "COMPLETED" {
		t.Fatalf("callback status = %q (detail %q), want COMPLETED", cb.Status, cb.Detail)
	}
	if got, want := flow.scans(), []string{"A", "B", "B", "C"}; strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("scanned nodes = %v, want %v", got, want)
	}
	if cb.ResultPayload["scans"] != float64(4) {
		t.Fatalf("scans = %v, want 4", cb.ResultPayload["scans"])
	}
	if cb.ResultPayload["recirculations"] != float64(1) {
		t.Fatalf("recirculations = %v, want 1", cb.ResultPayload["recirculations"])
	}
}

// NO_ROUTE fails the task via the callback, with flow's detail.
func TestLiveWalkNoRouteFailsTask(t *testing.T) {
	flow := newFakeFlow(t, func(scan int, node string) routingDecision {
		return routingDecision{Action: "NO_ROUTE", Detail: "no path from A to target"}
	})

	if res := postTask(t, walkTask(flow)); res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}
	cb := flow.awaitCallback(t)
	if cb.Status != "FAILED" {
		t.Fatalf("callback status = %q, want FAILED", cb.Status)
	}
	if !strings.Contains(cb.Detail, "no path from A to target") {
		t.Fatalf("detail = %q, want flow's NO_ROUTE detail", cb.Detail)
	}
	if cb.ResultPayload["walked"] != true {
		t.Fatalf("walked = %v, want true on the failed walk result", cb.ResultPayload["walked"])
	}
}

// Safety rail: a walk that never completes (flow always HOLDs) is failed once it exceeds the
// 500-scan budget instead of looping forever.
func TestLiveWalkScanBudget(t *testing.T) {
	flow := newFakeFlow(t, func(scan int, node string) routingDecision {
		return routingDecision{Action: "HOLD", Detail: "forever full"}
	})

	if res := postTask(t, walkTask(flow)); res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}
	cb := flow.awaitCallback(t)
	if cb.Status != "FAILED" {
		t.Fatalf("callback status = %q, want FAILED", cb.Status)
	}
	if !strings.Contains(cb.Detail, "walk exceeded scan budget") {
		t.Fatalf("detail = %q, want scan-budget failure", cb.Detail)
	}
	if got := len(flow.scans()); got != maxWalkScans {
		t.Fatalf("scans before giving up = %d, want %d", got, maxWalkScans)
	}
}

// A CONVEY without an entryNode keeps the existing atomic behaviour: no topology fetch, no scans,
// the plain async simulation completes.
func TestConveyWithoutEntryNodeStaysAtomic(t *testing.T) {
	flow := newFakeFlow(t, func(scan int, node string) routingDecision {
		t.Error("atomic CONVEY must not scan")
		return routingDecision{Action: "EXCEPTION"}
	})

	req := walkTask(flow)
	delete(req.Payload, "entryNode")
	if res := postTask(t, req); res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}
	cb := flow.awaitCallback(t)
	if cb.Status != "COMPLETED" {
		t.Fatalf("callback status = %q, want COMPLETED", cb.Status)
	}
	if cb.ResultPayload["walked"] != nil {
		t.Fatalf("walked = %v, want absent on the atomic path", cb.ResultPayload["walked"])
	}
	if got := flow.scans(); len(got) != 0 {
		t.Fatalf("atomic CONVEY scanned %v, want no scans", got)
	}
}

// Fault injection still applies to live walks: with faultEvery=1 the walk fails before starting,
// without scanning a single node.
func TestLiveWalkFaultInjectionFailsBeforeWalking(t *testing.T) {
	taskSeq.Store(0)
	faultEvery.Store(1)
	defer func() { faultEvery.Store(0); taskSeq.Store(0) }()

	flow := newFakeFlow(t, func(scan int, node string) routingDecision {
		t.Error("faulted walk must not scan")
		return routingDecision{Action: "EXCEPTION"}
	})

	if res := postTask(t, walkTask(flow)); res.Status != "ACCEPTED" {
		t.Fatalf("immediate status = %q, want ACCEPTED", res.Status)
	}
	cb := flow.awaitCallback(t)
	if cb.Status != "FAILED" {
		t.Fatalf("callback status = %q, want FAILED", cb.Status)
	}
	if cb.ResultPayload["fault"] != true {
		t.Fatalf("fault = %v, want true", cb.ResultPayload["fault"])
	}
	if got := flow.scans(); len(got) != 0 {
		t.Fatalf("faulted walk scanned %v, want no scans", got)
	}
}

// Edge travel time is cost(m) ÷ speedMps; latencyOverrideMs >= 0 overrides per edge.
func TestEdgeTravelHonoursSpeedAndOverride(t *testing.T) {
	latencyOverrideMs.Store(-1) // per-command defaults → real physics
	defer latencyOverrideMs.Store(0)

	setSpeedMps(0) // unset → 0.5 m/s default
	if d := edgeTravel(1); d != 2*time.Second {
		t.Fatalf("1 m at default 0.5 m/s = %v, want 2s", d)
	}
	setSpeedMps(2)
	defer setSpeedMps(defaultSpeedMps)
	if d := edgeTravel(3); d != 1500*time.Millisecond {
		t.Fatalf("3 m at 2 m/s = %v, want 1.5s", d)
	}

	latencyOverrideMs.Store(7)
	if d := edgeTravel(100); d != 7*time.Millisecond {
		t.Fatalf("override edge travel = %v, want 7ms", d)
	}
	if d := holdWait(); d != 7*time.Millisecond {
		t.Fatalf("override hold dwell = %v, want 7ms", d)
	}
}

// POST /config accepts speedMps and GET /config reports it (live-tunable like latency/faults).
func TestConfigSpeedMps(t *testing.T) {
	defer setSpeedMps(defaultSpeedMps)

	body := strings.NewReader(`{"speedMps": 3.5}`)
	rec := httptest.NewRecorder()
	handleConfig(rec, httptest.NewRequest(http.MethodPost, "/config", body))
	if rec.Code != http.StatusOK {
		t.Fatalf("POST /config status = %d, want 200", rec.Code)
	}

	rec = httptest.NewRecorder()
	handleConfig(rec, httptest.NewRequest(http.MethodGet, "/config", nil))
	var cfg configView
	if err := json.Unmarshal(rec.Body.Bytes(), &cfg); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if cfg.SpeedMps != 3.5 {
		t.Fatalf("GET /config speedMps = %v, want 3.5", cfg.SpeedMps)
	}
}

// flowBaseURL strips the device-task result path down to scheme://host:port.
func TestFlowBaseURL(t *testing.T) {
	base, err := flowBaseURL("http://flow-orchestrator:8085/api/flow/device-tasks/abc/result")
	if err != nil {
		t.Fatalf("flowBaseURL: %v", err)
	}
	if base != "http://flow-orchestrator:8085" {
		t.Fatalf("base = %q, want http://flow-orchestrator:8085", base)
	}
	if _, err := flowBaseURL("/api/flow/device-tasks/abc/result"); err == nil {
		t.Fatal("relative callbackUrl should fail base derivation")
	}
}
