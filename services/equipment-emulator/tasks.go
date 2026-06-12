package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sort"
	"strings"
	"time"
)

// tasks.go implements the uniform device contract POST /tasks for every equipment family.

// deviceTaskRequest is the uniform device contract envelope (build.md §8) as posted by the
// flow-orchestrator's synchronous HTTP DeviceClient. Unlike a per-family adapter, the emulator
// needs the {@code family} (flow includes it) to pick the right command set and per-family state.
type deviceTaskRequest struct {
	TaskID      string                 `json:"taskId"`
	WarehouseID string                 `json:"warehouseId"`
	EquipmentID string                 `json:"equipmentId"`
	Family      string                 `json:"family"`
	Command     string                 `json:"command"`
	Payload     map[string]interface{} `json:"payload"`
	// CallbackURL, when set by flow, switches this task to asynchronous: the emulator acks ACCEPTED
	// now and POSTs the terminal result here after the simulated processing time. Empty = synchronous.
	CallbackURL string `json:"callbackUrl"`
}

// deviceTaskResult is the synchronous reply; status is COMPLETED or FAILED.
type deviceTaskResult struct {
	Status        string                 `json:"status"`
	Detail        string                 `json:"detail"`
	ResultPayload map[string]interface{} `json:"resultPayload"`
}

// commandsByFamily are the moves each simulated family accepts — the union of the per-family
// adapters' supportedCommands, consolidated here.
// RELOCATE/BIN_RELOCATE (ADR-0009): a WCS-directed dig-out shuttle move — the payload tells the
// device exactly which HU to take from which slot to which slot; the emulator just sleeps one
// shuttle-move latency and reports back. No internal decision-making.
var commandsByFamily = map[string]map[string]bool{
	"ASRS":      {"STORE": true, "RETRIEVE": true, "RELOCATE": true, "SCAN": true},
	"CONVEYOR":  {"CONVEY": true, "DIVERT": true, "MERGE": true, "SCAN": true},
	"AMR":       {"TRANSPORT": true, "MOVE": true, "SCAN": true},
	"AUTOSTORE": {"BIN_STORE": true, "BIN_RETRIEVE": true, "BIN_RELOCATE": true, "SCAN": true},
}

// supportedFamilies lists the families the emulator simulates (sorted, for stable info output).
func supportedFamilies() []string {
	out := make([]string, 0, len(commandsByFamily))
	for f := range commandsByFamily {
		out = append(out, f)
	}
	sort.Strings(out)
	return out
}

// huRef renders the handling unit a task moves as a human-readable log fragment: the HU code when
// the payload carries one ("hu DEMO-HU-003"), the huId as fallback, "no hu reference" otherwise.
// Device-task payloads from flow carry huCode/huId; log lines must never show a bare UUID when a
// code is available.
func huRef(payload map[string]interface{}) string {
	if code := payloadString(payload, "huCode"); code != "" {
		return "hu " + code
	}
	if id := payloadString(payload, "huId"); id != "" {
		return "hu(id-only) " + id
	}
	return "no hu reference"
}

// routeRef renders the task's route from the payload fields flow sets (entryNode/destinationNode
// for conveyor legs, fromLocationId/toLocationId for relocations), or "" when the payload carries
// no route.
func routeRef(payload map[string]interface{}) string {
	if from, to := payloadString(payload, "entryNode"), payloadString(payload, "destinationNode"); from != "" || to != "" {
		return " route " + orUnknown(from) + " -> " + orUnknown(to)
	}
	if from, to := payloadString(payload, "fromLocationId"), payloadString(payload, "toLocationId"); from != "" || to != "" {
		return " relocate " + orUnknown(from) + " -> " + orUnknown(to)
	}
	return ""
}

func orUnknown(s string) string {
	if s == "" {
		return "?"
	}
	return s
}

// handleTask simulates executing a device task for any family: it validates the family and command
// and echoes a COMPLETED result (or FAILED for an unknown family / command). There is no emulator
// flag gate here — flow only routes to the emulator while the flag is ON, so it always simulates.
func handleTask(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var req deviceTaskRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		log.Printf("%s: WARNING device task POST rejected: body is not valid JSON (%v); caller gets HTTP 400 and no task runs", serviceName, err)
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	family := strings.ToUpper(strings.TrimSpace(req.Family))
	commands, known := commandsByFamily[family]
	if !known {
		log.Printf("%s: WARNING task %s (%s, equipment %s) rejected: unknown family %q, emulator only simulates %v; task FAILED, nothing moved",
			serviceName, req.TaskID, huRef(req.Payload), orUnknown(req.EquipmentID), req.Family, supportedFamilies())
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "unknown equipment family: " + req.Family,
		})
		return
	}
	if !commands[req.Command] {
		log.Printf("%s: WARNING task %s (%s, equipment %s) rejected: command %q is not in %s's command set; task FAILED, nothing moved",
			serviceName, req.TaskID, huRef(req.Payload), orUnknown(req.EquipmentID), req.Command, family)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "unsupported command for " + family + ": " + req.Command,
		})
		return
	}

	// Asynchronous path: when flow supplies a callbackUrl, ack ACCEPTED immediately and run the task
	// (sleep + fault + record) in the background, POSTing the terminal result back to flow. This is
	// the non-blocking device contract — flow no longer holds a request/transaction open for the
	// simulated processing time.
	if req.CallbackURL != "" {
		_ = json.NewEncoder(w).Encode(deviceTaskResult{Status: "ACCEPTED", Detail: "dispatched; result will follow via callback"})
		// A CONVEY whose payload carries an entryNode runs the live conveyor walk (ADR-0008
		// Phase 3d-2): scan at every node, obey flow's routing answers, travel edges at speedMps.
		// Without an entryNode the atomic simulation below stays byte-for-byte unchanged.
		if isLiveWalk(family, req) {
			log.Printf("%s: task %s accepted: %s %s for %s%s, equipment %s; running as live conveyor walk, result via callback",
				serviceName, req.TaskID, family, req.Command, huRef(req.Payload), routeRef(req.Payload), orUnknown(req.EquipmentID))
			go func() {
				postCallback(req.CallbackURL, req.TaskID, runLiveWalk(family, req))
			}()
			return
		}
		log.Printf("%s: task %s accepted: %s %s for %s%s, equipment %s; simulating async, result via callback",
			serviceName, req.TaskID, family, req.Command, huRef(req.Payload), routeRef(req.Payload), orUnknown(req.EquipmentID))
		go func() {
			postCallback(req.CallbackURL, req.TaskID, runTask(family, req))
		}()
		return
	}

	// Synchronous fallback (no callbackUrl, e.g. tests): run inline and return the terminal result.
	log.Printf("%s: task %s accepted: %s %s for %s%s, equipment %s; no callbackUrl so simulating synchronously",
		serviceName, req.TaskID, family, req.Command, huRef(req.Payload), routeRef(req.Payload), orUnknown(req.EquipmentID))
	_ = json.NewEncoder(w).Encode(runTask(family, req))
}

// runTask simulates executing a validated task: it sleeps the per-command latency, maybe injects a
// fault, records the outcome in the telemetry state, and returns the terminal result.
func runTask(family string, req deviceTaskRequest) deviceTaskResult {
	d := commandLatency(family, req.Command)

	// Conveyor leg: model loop recirculation (ADR-0007 R2). A recirculating tote takes extra loop
	// time before it diverts to its destination, so arrival order diverges from dispatch order; the
	// divert/recirculate decision points are reported back for the HU transport trace (R4).
	var decisions []map[string]interface{}
	if req.Command == "CONVEY" {
		extra, dec := conveyJourney()
		d += extra
		decisions = dec
		// All but the final DIVERTED decision are recirculations: make that decision visible per
		// tote, with the policy that caused it and the cost it adds.
		if recircs := len(dec) - 1; recircs > 0 {
			log.Printf("%s: task %s CONVEY for %s: recirculated %d time(s) at sorter, missed divert (destination busy, recircEvery=%d policy); adds %s loop time before divert to %s",
				serviceName, req.TaskID, huRef(req.Payload), recircs, recircEvery.Load(), extra, orUnknown(payloadString(req.Payload, "destinationNode")))
		}
	}

	time.Sleep(d)

	// Inject a deterministic simulated equipment fault when configured (OPENWCS_EMULATOR_FAULT_RATE
	// or POST /config): 1 in every N tasks fails.
	if shouldFault() {
		sim.recordFailed(family, req.Command)
		log.Printf("%s: WARNING task %s (%s %s for %s%s, equipment %s) SIMULATED FAULT after %s: deterministic fault injection hit (faultEvery=%d); task FAILED, flow must retry or resolve",
			serviceName, req.TaskID, family, req.Command, huRef(req.Payload), routeRef(req.Payload), orUnknown(req.EquipmentID), d, faultEvery.Load())
		return deviceTaskResult{
			Status: "FAILED",
			Detail: strings.ToLower(family) + " simulated equipment fault on " + req.Command,
			ResultPayload: map[string]interface{}{
				"command":    req.Command,
				"family":     family,
				"equipment":  req.EquipmentID,
				"durationMs": d.Milliseconds(),
				"simulated":  true,
				"fault":      true,
			},
		}
	}

	sim.recordCompleted(family, req.Command)
	log.Printf("%s: task %s COMPLETED: %s %s for %s%s, equipment %s, simulated in %s",
		serviceName, req.TaskID, family, req.Command, huRef(req.Payload), routeRef(req.Payload), orUnknown(req.EquipmentID), d)
	payload := map[string]interface{}{
		"command":    req.Command,
		"family":     family,
		"equipment":  req.EquipmentID,
		"durationMs": d.Milliseconds(),
		"simulated":  true,
	}
	if decisions != nil {
		// All but the final DIVERTED decision are recirculations.
		payload["recirculations"] = len(decisions) - 1
		payload["decisions"] = decisions
	}
	return deviceTaskResult{
		Status:        "COMPLETED",
		Detail:        strings.ToLower(family) + " simulated " + req.Command,
		ResultPayload: payload,
	}
}
