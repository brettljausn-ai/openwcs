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
}

// deviceTaskResult is the synchronous reply; status is COMPLETED or FAILED.
type deviceTaskResult struct {
	Status        string                 `json:"status"`
	Detail        string                 `json:"detail"`
	ResultPayload map[string]interface{} `json:"resultPayload"`
}

// commandsByFamily are the moves each simulated family accepts — the union of the per-family
// adapters' supportedCommands, consolidated here.
var commandsByFamily = map[string]map[string]bool{
	"ASRS":      {"STORE": true, "RETRIEVE": true, "SCAN": true},
	"CONVEYOR":  {"CONVEY": true, "DIVERT": true, "MERGE": true, "SCAN": true},
	"AMR":       {"TRANSPORT": true, "MOVE": true, "SCAN": true},
	"AUTOSTORE": {"BIN_STORE": true, "BIN_RETRIEVE": true, "SCAN": true},
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
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	family := strings.ToUpper(strings.TrimSpace(req.Family))
	commands, known := commandsByFamily[family]
	if !known {
		log.Printf("%s: task %s rejected unknown family %q", serviceName, req.TaskID, req.Family)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "unknown equipment family: " + req.Family,
		})
		return
	}
	if !commands[req.Command] {
		log.Printf("%s: task %s rejected unsupported command %q for family %s", serviceName, req.TaskID, req.Command, family)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "unsupported command for " + family + ": " + req.Command,
		})
		return
	}

	// Simulate the equipment taking time to execute. The device contract is still synchronous
	// (flow blocks on this response), so the per-command durations are kept modest; a non-blocking
	// async contract is the follow-up (see EMULATOR-CONSOLIDATION.md, Phase 3b).
	d := commandLatency(family, req.Command)
	time.Sleep(d)

	// Inject a deterministic simulated equipment fault when configured (OPENWCS_EMULATOR_FAULT_RATE
	// or POST /config): 1 in every N tasks fails.
	if shouldFault() {
		sim.recordFailed(family, req.Command)
		log.Printf("%s: task %s family=%s command=%s SIMULATED FAULT after %s", serviceName, req.TaskID, family, req.Command, d)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
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
		})
		return
	}

	sim.recordCompleted(family, req.Command)
	log.Printf("%s: executed task %s family=%s command=%s equipment=%s in %s", serviceName, req.TaskID, family, req.Command, req.EquipmentID, d)
	_ = json.NewEncoder(w).Encode(deviceTaskResult{
		Status: "COMPLETED",
		Detail: strings.ToLower(family) + " simulated " + req.Command,
		ResultPayload: map[string]interface{}{
			"command":    req.Command,
			"family":     family,
			"equipment":  req.EquipmentID,
			"durationMs": d.Milliseconds(),
			"simulated":  true,
		},
	})
}
