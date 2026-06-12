package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
)

// tasks.go implements the uniform device contract POST /tasks for the AMR Geek+ adapter.

// deviceTaskRequest is the uniform device contract envelope (build.md §8) as posted by the
// flow-orchestrator's synchronous HTTP DeviceClient.
type deviceTaskRequest struct {
	TaskID      string                 `json:"taskId"`
	WarehouseID string                 `json:"warehouseId"`
	EquipmentID string                 `json:"equipmentId"`
	Command     string                 `json:"command"`
	Payload     map[string]interface{} `json:"payload"`
}

// deviceTaskResult is the synchronous reply; status is COMPLETED or FAILED.
type deviceTaskResult struct {
	Status        string                 `json:"status"`
	Detail        string                 `json:"detail"`
	ResultPayload map[string]interface{} `json:"resultPayload"`
}

// supportedCommands are the moves this AMR adapter accepts.
var supportedCommands = map[string]bool{"TRANSPORT": true, "MOVE": true, "SCAN": true}

// huRef renders the handling unit a task moves as a human-readable log fragment: the HU code when
// the payload carries one ("hu DEMO-HU-003"), the huId as fallback. Log lines must never show a
// bare UUID when the payload carries a code.
func huRef(payload map[string]interface{}) string {
	if s, ok := payload["huCode"].(string); ok && strings.TrimSpace(s) != "" {
		return "hu " + strings.TrimSpace(s)
	}
	if s, ok := payload["huId"].(string); ok && strings.TrimSpace(s) != "" {
		return "hu(id-only) " + strings.TrimSpace(s)
	}
	return "no hu reference"
}

// handleTask is the real-adapter device entrypoint: it validates the command, then fails because no
// live hardware is connected (the RCS fleet-manager client is unimplemented). Hardware emulation
// moved to the equipment-emulator service, so flow only routes here when the emulator flag is OFF.
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
	if !supportedCommands[req.Command] {
		log.Printf("%s: WARNING task %s (%s, equipment %s) rejected: command %q is not in the %s command set; task FAILED, nothing moved",
			serviceName, req.TaskID, huRef(req.Payload), req.EquipmentID, req.Command, family)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "unsupported command: " + req.Command,
		})
		return
	}

	// No real-hardware code exists yet (the RCS client is the TODO in main.go's deviceLoop), and
	// emulation now lives in the equipment-emulator service — so a task only reaches this real adapter
	// when the emulator flag is OFF, where it fails cleanly as "not connected".
	log.Printf("%s: WARNING task %s refused: %s %s for %s (equipment %s) not executed, no live %s fleet is connected (RCS fleet-manager client unimplemented; tasks reach this adapter only while the emulator flag is OFF); task FAILED, turn the emulator ON or connect real hardware",
		serviceName, req.TaskID, family, req.Command, huRef(req.Payload), req.EquipmentID, strings.ToLower(family))
	_ = json.NewEncoder(w).Encode(deviceTaskResult{
		Status: "FAILED",
		Detail: "hardware not connected (no live " + strings.ToLower(family) + " adapter configured)",
	})
}
