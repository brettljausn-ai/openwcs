package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"time"
)

// walk.go implements the live conveyor walk (ADR-0008 Phase 3d-2): a CONVEY task whose payload
// carries an entryNode is executed as real hardware would: the tote starts at entryNode, is
// scanned at every node, and the emulator OBEYS the flow controller's routing answers
// (POST /api/flow/conveyor/routing-requests). It decides nothing itself: ROUTE → travel the edge
// at speedMps, HOLD → dwell and rescan the same node, COMPLETE → arrival, NO_ROUTE/EXCEPTION →
// task failure. Loop recirculation now EMERGES from flow's HOLD decisions, so the internal
// recircEvery simulation (recirc.go) deliberately does not apply to live walks.

// speedMpsBits holds the conveyor speed (m/s) as math.Float64bits, atomically read/written so
// GET/POST /config can tune it live while walks are in flight. <= 0 falls back to the default.
var speedMpsBits atomic.Uint64

// defaultSpeedMps is the ADR-0008 conveyor speed: 0.5 m/s.
const defaultSpeedMps = 0.5

// maxWalkScans is the safety rail: a walk that scans more than this many times is failed
// ("walk exceeded scan budget") instead of looping forever on a pathological topology/decision.
const maxWalkScans = 500

// maxNoRouteRetries / noRouteDwell: how often a NO_ROUTE answer is retried on the same node before
// the walk fails — absorbs the controller's dispatch-transaction race (the route plan commits
// milliseconds after the task is acked, but the first scan can beat it).
const maxNoRouteRetries = 4
const noRouteDwell = 500 * time.Millisecond

// holdDwell is the time a held tote waits before rescanning the same node (~1 s per ADR-0008).
// Like edge travel, latencyOverrideMs >= 0 overrides it (in ms) so demos/tests stay tunable.
const holdDwell = time.Second

// walkClient is the HTTP client for topology fetches and per-node scans (5 s per call).
var walkClient = &http.Client{Timeout: 5 * time.Second}

// speedMps returns the live conveyor speed in m/s, falling back to the 0.5 m/s default.
func speedMps() float64 {
	if v := math.Float64frombits(speedMpsBits.Load()); v > 0 {
		return v
	}
	return defaultSpeedMps
}

// setSpeedMps stores the live conveyor speed (m/s); values <= 0 are ignored by speedMps().
func setSpeedMps(v float64) {
	speedMpsBits.Store(math.Float64bits(v))
}

// edgeTravel is the time to traverse an edge of costMetres at the live speed. Mirroring how
// latencyOverrideMs forces task latency, an override >= 0 acts as a per-EDGE override in ms,
// so latencyOverrideMs=0 makes walks instantaneous for tests/demos.
func edgeTravel(costMetres float64) time.Duration {
	if ov := latencyOverrideMs.Load(); ov >= 0 {
		return time.Duration(ov) * time.Millisecond
	}
	return time.Duration(costMetres / speedMps() * float64(time.Second))
}

// holdWait is the dwell before rescanning a HELD node, honouring the same live override.
func holdWait() time.Duration {
	if ov := latencyOverrideMs.Load(); ov >= 0 {
		return time.Duration(ov) * time.Millisecond
	}
	return holdDwell
}

// isLiveWalk reports whether a validated task should run the live conveyor walk: a CONVEYOR
// CONVEY whose payload carries a non-empty entryNode, with a warehouseId and the async callback
// (the callbackUrl is also how the flow base URL is derived). Anything else keeps the existing
// atomic behaviour byte-for-byte (including recircEvery).
func isLiveWalk(family string, req deviceTaskRequest) bool {
	return family == "CONVEYOR" && req.Command == "CONVEY" &&
		payloadString(req.Payload, "entryNode") != "" &&
		walkWarehouseID(req) != "" &&
		req.CallbackURL != ""
}

// walkWarehouseID resolves the warehouse for the walk: the task envelope's warehouseId (flow
// always sets it), falling back to a payload field.
func walkWarehouseID(req deviceTaskRequest) string {
	if id := strings.TrimSpace(req.WarehouseID); id != "" {
		return id
	}
	return payloadString(req.Payload, "warehouseId")
}

// payloadString returns payload[key] when it is a non-empty string ("" otherwise).
func payloadString(payload map[string]interface{}, key string) string {
	if payload == nil {
		return ""
	}
	if s, ok := payload[key].(string); ok {
		return strings.TrimSpace(s)
	}
	return ""
}

// walkBarcode is what the conveyor "reads" at each scan point: the HU code, falling back to the
// huId string when no code is present.
func walkBarcode(payload map[string]interface{}) string {
	if code := payloadString(payload, "huCode"); code != "" {
		return code
	}
	return payloadString(payload, "huId")
}

// flowBaseURL derives the flow-orchestrator base (scheme://host[:port]) from the task's
// callbackUrl (flow sets it to {selfBaseUrl}/api/flow/device-tasks/{id}/result), so the walk's
// topology fetch and per-node scans go back to the same flow instance that dispatched the task.
func flowBaseURL(callbackURL string) (string, error) {
	u, err := url.Parse(callbackURL)
	if err != nil {
		return "", fmt.Errorf("invalid callbackUrl %q: %w", callbackURL, err)
	}
	if u.Scheme == "" || u.Host == "" {
		return "", fmt.Errorf("callbackUrl %q has no scheme/host to derive the flow base from", callbackURL)
	}
	return u.Scheme + "://" + u.Host, nil
}

// topologyEdge / topologyDoc decode the slice of GET /api/flow/conveyor/topology the walk needs
// (contracts/openapi/flow-orchestrator.yaml: EdgeDto{fromCode, toCode, exitCode, cost}).
type topologyEdge struct {
	FromCode string  `json:"fromCode"`
	ToCode   string  `json:"toCode"`
	ExitCode string  `json:"exitCode"`
	Cost     float64 `json:"cost"`
}

type topologyDoc struct {
	Edges []topologyEdge `json:"edges"`
}

// routingDecision is flow's answer to a scan (RoutingDecision in the OpenAPI contract).
type routingDecision struct {
	Action        string `json:"action"`
	ExitCode      string `json:"exitCode"`
	ToNode        string `json:"toNode"`
	CurrentTarget string `json:"currentTarget"`
	TargetReached string `json:"targetReached"`
	Detail        string `json:"detail"`
}

// fetchTopology loads the warehouse's conveyor graph once per walk and indexes the edge costs
// as fromCode → toCode → cost (metres). One retry on any HTTP error.
func fetchTopology(base, warehouseID string) (map[string]map[string]float64, error) {
	u := base + "/api/flow/conveyor/topology?warehouseId=" + url.QueryEscape(warehouseID)
	var doc topologyDoc
	if err := getJSONWithRetry(u, &doc); err != nil {
		return nil, fmt.Errorf("topology fetch: %w", err)
	}
	costs := map[string]map[string]float64{}
	for _, e := range doc.Edges {
		m := costs[e.FromCode]
		if m == nil {
			m = map[string]float64{}
			costs[e.FromCode] = m
		}
		m[e.ToCode] = e.Cost
	}
	return costs, nil
}

// postScan reports the barcode at a node and returns flow's routing decision. One retry on any
// HTTP error.
func postScan(base, warehouseID, node, barcode string) (routingDecision, error) {
	body, _ := json.Marshal(map[string]string{
		"warehouseId": warehouseID,
		"node":        node,
		"barcode":     barcode,
	})
	var dec routingDecision
	if err := postJSONWithRetry(base+"/api/flow/conveyor/routing-requests", body, &dec); err != nil {
		return dec, fmt.Errorf("scan at %s: %w", node, err)
	}
	return dec, nil
}

// getJSONWithRetry GETs a JSON document, retrying once on any transport/HTTP error.
func getJSONWithRetry(u string, out interface{}) error {
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		resp, err := walkClient.Get(u)
		if err != nil {
			lastErr = err
			continue
		}
		lastErr = decodeJSONResponse(resp, out)
		if lastErr == nil {
			return nil
		}
	}
	return lastErr
}

// postJSONWithRetry POSTs a JSON body, retrying once on any transport/HTTP error.
func postJSONWithRetry(u string, body []byte, out interface{}) error {
	var lastErr error
	for attempt := 0; attempt < 2; attempt++ {
		resp, err := walkClient.Post(u, "application/json", bytes.NewReader(body))
		if err != nil {
			lastErr = err
			continue
		}
		lastErr = decodeJSONResponse(resp, out)
		if lastErr == nil {
			return nil
		}
	}
	return lastErr
}

func decodeJSONResponse(resp *http.Response, out interface{}) error {
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// runLiveWalk executes a CONVEY task in live walk mode: scan at every node, obey flow's routing
// answer, travel each edge at speedMps. Returns the terminal result the caller POSTs back to the
// task's callbackUrl. Fault injection (faultEvery) still applies (a faulted walk fails before it
// starts, like every other command. recircEvery does NOT apply: recirculation now comes from
// flow's HOLD decisions.
func runLiveWalk(family string, req deviceTaskRequest) deviceTaskResult {
	start := time.Now()
	entryNode := payloadString(req.Payload, "entryNode")
	warehouseID := walkWarehouseID(req)
	barcode := walkBarcode(req.Payload)

	if shouldFault() {
		sim.recordFailed(family, req.Command)
		log.Printf("%s: task %s family=%s command=%s SIMULATED FAULT before live walk", serviceName, req.TaskID, family, req.Command)
		return deviceTaskResult{
			Status: "FAILED",
			Detail: "conveyor simulated equipment fault on " + req.Command,
			ResultPayload: map[string]interface{}{
				"command":    req.Command,
				"family":     family,
				"equipment":  req.EquipmentID,
				"durationMs": time.Since(start).Milliseconds(),
				"simulated":  true,
				"walked":     true,
				"fault":      true,
			},
		}
	}

	fail := func(detail string, scans, holds int, decisions []map[string]interface{}) deviceTaskResult {
		sim.recordFailed(family, req.Command)
		log.Printf("%s: task %s live walk FAILED at scan %d: %s", serviceName, req.TaskID, scans, detail)
		return deviceTaskResult{
			Status: "FAILED",
			Detail: detail,
			ResultPayload: map[string]interface{}{
				"command":        req.Command,
				"family":         family,
				"equipment":      req.EquipmentID,
				"durationMs":     time.Since(start).Milliseconds(),
				"simulated":      true,
				"walked":         true,
				"scans":          scans,
				"recirculations": holds,
				"decisions":      decisions,
			},
		}
	}

	base, err := flowBaseURL(req.CallbackURL)
	if err != nil {
		return fail(err.Error(), 0, 0, nil)
	}
	if barcode == "" {
		return fail("live walk needs a barcode (payload huCode or huId)", 0, 0, nil)
	}

	costs, err := fetchTopology(base, warehouseID)
	if err != nil {
		return fail(err.Error(), 0, 0, nil)
	}

	current := entryNode
	scans, holds := 0, 0
	noRouteRetries := 0
	var decisions []map[string]interface{}
	for {
		if scans >= maxWalkScans {
			return fail("walk exceeded scan budget", scans, holds, decisions)
		}
		scans++
		dec, err := postScan(base, warehouseID, current, barcode)
		if err != nil {
			return fail(err.Error(), scans, holds, decisions)
		}
		switch dec.Action {
		case "ROUTE":
			if dec.ToNode == "" {
				return fail("ROUTE decision at "+current+" carries no toNode", scans, holds, decisions)
			}
			cost := costs[current][dec.ToNode]
			if cost <= 0 {
				cost = 1 // edge not in the topology (or cost unset) → assume 1 m
			}
			time.Sleep(edgeTravel(cost))
			decisions = append(decisions, map[string]interface{}{
				"point":    current,
				"event":    "ROUTED",
				"decision": "to " + dec.ToNode,
			})
			current = dec.ToNode
		case "HOLD":
			decisions = append(decisions, map[string]interface{}{
				"point":    current,
				"event":    "HELD",
				"decision": dec.Detail,
			})
			holds++
			time.Sleep(holdWait())
			// rescan the SAME node
		case "COMPLETE":
			decisions = append(decisions, map[string]interface{}{
				"point":    current,
				"event":    "ARRIVED",
				"decision": dec.TargetReached,
			})
			elapsed := time.Since(start)
			sim.recordCompleted(family, req.Command)
			sim.recordWalk()
			log.Printf("%s: task %s live walk COMPLETED at %s after %d scans (%d holds) in %s",
				serviceName, req.TaskID, current, scans, holds, elapsed)
			return deviceTaskResult{
				Status: "COMPLETED",
				Detail: "conveyor walked " + req.Command + " to " + current,
				ResultPayload: map[string]interface{}{
					"command":        req.Command,
					"family":         family,
					"equipment":      req.EquipmentID,
					"durationMs":     elapsed.Milliseconds(),
					"simulated":      true,
					"walked":         true,
					"scans":          scans,
					"recirculations": holds,
					"decisions":      decisions,
				},
			}
		case "NO_ROUTE":
			// Real hardware rescans: a NO_ROUTE on the first scans is usually the dispatch race —
			// the controller's route-plan transaction has not committed yet when the tote hits the
			// scanner. Retry the same node a few times before declaring the walk dead.
			if noRouteRetries < maxNoRouteRetries {
				noRouteRetries++
				decisions = append(decisions, map[string]interface{}{
					"point": current, "event": "RESCAN",
					"decision": fmt.Sprintf("no route yet — rescan %d/%d", noRouteRetries, maxNoRouteRetries),
				})
				time.Sleep(noRouteDwell)
				continue
			}
			detail := dec.Detail
			if detail == "" {
				detail = "NO_ROUTE at " + current
			}
			return fail(detail, scans, holds, decisions)
		case "EXCEPTION":
			detail := dec.Detail
			if detail == "" {
				detail = "EXCEPTION at " + current
			}
			return fail(detail, scans, holds, decisions)
		default:
			return fail("unknown routing action "+dec.Action+" at "+current, scans, holds, decisions)
		}
	}
}
