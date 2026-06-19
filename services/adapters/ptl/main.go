// Package main is the Pick-to-light (PTL) device adapter.
//
// Transport: Pick-to-light controller (HTTP/serial).
// It implements the uniform internal device contract (build.md §8): the gtp service POSTs a device
// task to drive put-to-light hardware (ILLUMINATE a light showing a pick quantity, or CLEAR it once
// the operator confirms), and the adapter replies COMPLETED/FAILED synchronously.
//
// This is a skeleton: an HTTP server exposing health/readiness probes, the device contract POST
// /tasks, and a GET /state view of the currently lit lights, plus a stub device-connection loop.
// Wire the real controller protocol (HTTP/serial) where indicated. Lit lights are tracked in an
// in-memory map (lost on restart), which is enough for the emulator-driven demo and the tests.
package main

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	serviceName = "ptl-adapter"
	family      = "PTL"
	transport   = "Pick-to-light controller (HTTP/serial)"
	defaultPort = "9098"
)

// Build metadata, injected via -ldflags at build time (see Dockerfile). Defaults cover `go run`.
var (
	version   = "0.1.0-SNAPSHOT"
	commit    = "dev"
	buildTime = "unknown"
)

func main() {
	// Mirror logs to a daily-rotated file for the System info screen (kept ~14 days). Stdout is
	// unaffected, so `docker logs` keeps working.
	if fw := newDailyLog(serviceName); fw != nil {
		log.SetOutput(io.MultiWriter(os.Stdout, fw))
	}

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
	mux.HandleFunc("/state", handleState)
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

	// Device connection loop (stub). Replace with the real PTL controller client:
	// maintain the connection/session over HTTP/serial, push light on/off frames, and reconcile the
	// lit set on reconnect.
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

// deviceTaskRequest is the uniform device contract envelope (build.md §8) as posted by the gtp
// service's synchronous HTTP DeviceClient. The PTL payload carries lightId (which light), qty (the
// number to show) and an optional color.
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

// supportedCommands are the PTL moves this adapter accepts: ILLUMINATE turns a light on showing a
// quantity, CLEAR turns it off.
var supportedCommands = map[string]bool{"ILLUMINATE": true, "CLEAR": true}

// litLight is one currently illuminated light: the quantity shown plus an optional color.
type litLight struct {
	Qty   int    `json:"qty"`
	Color string `json:"color,omitempty"`
}

// lights is the in-memory set of currently lit lights, keyed by lightId. It is the adapter's view of
// the controller and what GET /state exposes for observability/tests. Lost on restart (a real
// controller is reconciled on reconnect in deviceLoop).
var lights = struct {
	mu  sync.Mutex
	lit map[string]litLight
}{lit: map[string]litLight{}}

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

// payloadInt returns payload[key] as an int when present (JSON numbers decode to float64). Missing or
// non-numeric values yield 0.
func payloadInt(payload map[string]interface{}, key string) int {
	if payload == nil {
		return 0
	}
	switch v := payload[key].(type) {
	case float64:
		return int(v)
	case int:
		return v
	}
	return 0
}

// handleTask is the device entrypoint: it validates the command, then drives the (emulated) light by
// updating the in-memory lit set. ILLUMINATE turns the light at payload.lightId on showing
// payload.qty (and optional payload.color); CLEAR turns it off; any other command FAILs.
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
		log.Printf("%s: WARNING task %s (equipment %s) rejected: command %q is not in the %s command set; task FAILED, nothing lit",
			serviceName, req.TaskID, req.EquipmentID, req.Command, family)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "unsupported command: " + req.Command,
		})
		return
	}

	lightID := payloadString(req.Payload, "lightId")
	if lightID == "" {
		log.Printf("%s: WARNING task %s (equipment %s) rejected: %s has no payload.lightId; task FAILED, no light addressed",
			serviceName, req.TaskID, req.EquipmentID, req.Command)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "FAILED",
			Detail: "missing payload.lightId",
		})
		return
	}

	switch req.Command {
	case "ILLUMINATE":
		qty := payloadInt(req.Payload, "qty")
		color := payloadString(req.Payload, "color")
		lights.mu.Lock()
		lights.lit[lightID] = litLight{Qty: qty, Color: color}
		lights.mu.Unlock()
		log.Printf("%s: task %s COMPLETED: ILLUMINATE light %s showing qty=%d color=%q (equipment %s)",
			serviceName, req.TaskID, lightID, qty, color, req.EquipmentID)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "COMPLETED",
			Detail: "illuminated light " + lightID,
			ResultPayload: map[string]interface{}{
				"command": "ILLUMINATE",
				"family":  family,
				"lightId": lightID,
				"qty":     qty,
				"color":   color,
				"lit":     true,
			},
		})
	case "CLEAR":
		lights.mu.Lock()
		delete(lights.lit, lightID)
		lights.mu.Unlock()
		log.Printf("%s: task %s COMPLETED: CLEAR light %s (equipment %s)",
			serviceName, req.TaskID, lightID, req.EquipmentID)
		_ = json.NewEncoder(w).Encode(deviceTaskResult{
			Status: "COMPLETED",
			Detail: "cleared light " + lightID,
			ResultPayload: map[string]interface{}{
				"command": "CLEAR",
				"family":  family,
				"lightId": lightID,
				"lit":     false,
			},
		})
	}
}

// handleState serves GET /state with the currently lit lights, e.g. {"lit": {"A-01": {"qty": 3}}}.
func handleState(w http.ResponseWriter, r *http.Request) {
	lights.mu.Lock()
	lit := make(map[string]litLight, len(lights.lit))
	for id, l := range lights.lit {
		lit[id] = l
	}
	lights.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"service": serviceName,
		"family":  family,
		"lit":     lit,
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
			// TODO: real hardware connection: maintain the PTL controller connection/session over
			// HTTP/serial, push light on/off frames, and reconcile the lit set on reconnect.
			log.Printf("%s: device heartbeat (stub; no live hardware connected)", serviceName)
		}
	}
}
