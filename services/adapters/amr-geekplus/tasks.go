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

// supportedCommands are the moves this AMR simulator accepts.
var supportedCommands = map[string]bool{"TRANSPORT": true, "MOVE": true, "SCAN": true}

// handleTask simulates executing a device task: it validates the command and, when the emulator is
// ON, echoes a COMPLETED result (or FAILED for an unknown command / emulator off). The real
// adapter would call the RCS fleet manager and reconcile the outcome asynchronously.
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

	// The simulator only runs when the hardware emulator is ON. With it OFF there is no live
	// adapter to drive (no real-hardware code exists yet), so the task fails cleanly.
	if !EmulatorOn() {
		log.Printf("%s: task %s rejected, emulator off", serviceName, req.TaskID)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "hardware not connected (emulator off; no live adapter configured)",
		})
		return
	}

	sim.recordCommand(req.Command)
	log.Printf("%s: executing task %s command=%s equipment=%s", serviceName, req.TaskID, req.Command, req.EquipmentID)
	_ = json.NewEncoder(w).Encode(deviceTaskResult{
		Status: "COMPLETED",
		Detail: strings.ToLower(family) + " simulated " + req.Command,
		ResultPayload: map[string]interface{}{
			"command":   req.Command,
			"equipment": req.EquipmentID,
			"simulated": true,
		},
	})
}
