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
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	if !supportedCommands[req.Command] {
		log.Printf("%s: task %s rejected unsupported command %q", serviceName, req.TaskID, req.Command)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "unsupported command: " + req.Command,
		})
		return
	}

	// No real-hardware code exists yet (the RCS client is the TODO in main.go's deviceLoop), and
	// emulation now lives in the equipment-emulator service — so a task only reaches this real adapter
	// when the emulator flag is OFF, where it fails cleanly as "not connected".
	log.Printf("%s: task %s (%s) not executed — no live hardware connected", serviceName, req.TaskID, req.Command)
	_ = json.NewEncoder(w).Encode(deviceTaskResult{
		Status: "FAILED",
		Detail: "hardware not connected (no live " + strings.ToLower(family) + " adapter configured)",
	})
}
