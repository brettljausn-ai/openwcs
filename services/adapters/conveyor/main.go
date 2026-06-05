// Package main is the Conveyor device adapter.
//
// Transport: Raw TCP / OPC-UA (PLC).
// It implements the uniform internal device contract (build.md §8): consume
// DeviceTaskRequested, drive the equipment, publish DeviceTaskResult and
// telemetry, and append physical movements to the transaction log.
//
// This is a skeleton: an HTTP server exposing health/readiness probes plus a
// stub device-connection loop. Wire the real protocol client and Kafka
// producer/consumer where indicated.
package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

const (
	serviceName = "conveyor-adapter"
	family      = "Conveyor"
	transport   = "Raw TCP / OPC-UA (PLC)"
	defaultPort = "9091"
)

// Build metadata, injected via -ldflags at build time (see Dockerfile). Defaults cover `go run`.
var (
	version   = "0.1.0-SNAPSHOT"
	commit    = "dev"
	buildTime = "unknown"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = defaultPort
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ready"))
	})
	mux.HandleFunc("/tasks", handleTask)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"service":   serviceName,
			"family":    family,
			"transport": transport,
			"status":    "skeleton",
			"version":   version,
			"commit":    commit,
			"buildTime": buildTime,
		})
	})

	srv := &http.Server{Addr: ":" + port, Handler: mux}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Device connection loop (stub). Replace with the real protocol client:
	// maintain the connection/session, frame/parse telegrams or call the
	// vendor REST/WebSocket API, and reconcile in-flight commands on reconnect.
	go deviceLoop(ctx)

	go func() {
		log.Printf("%s listening on :%s (family=%s, transport=%s)", serviceName, port, family, transport)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http server error: %v", err)
		}
	}()

	<-ctx.Done()
	log.Printf("%s shutting down", serviceName)
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}

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

// supportedCommands are the moves this conveyor simulator accepts.
var supportedCommands = map[string]bool{"CONVEY": true, "DIVERT": true, "MERGE": true, "SCAN": true}

// handleTask simulates executing a device task: it validates the command and echoes a
// COMPLETED result (or FAILED for an unknown command). The real adapter would drive the PLC
// and reconcile the outcome asynchronously.
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

	log.Printf("%s: executing task %s command=%s equipment=%s", serviceName, req.TaskID, req.Command, req.EquipmentID)
	_ = json.NewEncoder(w).Encode(deviceTaskResult{
		Status: "COMPLETED",
		Detail: "conveyor simulated " + req.Command,
		ResultPayload: map[string]interface{}{
			"command":   req.Command,
			"equipment": req.EquipmentID,
			"simulated": true,
		},
	})
}

func deviceLoop(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// TODO: poll/heartbeat the equipment; publish telemetry to Kafka.
			log.Printf("%s: device heartbeat (stub)", serviceName)
		}
	}
}
