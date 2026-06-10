// Package main is the equipment emulator: one service that simulates ALL device families
// (ASRS, Conveyor, AMR, AutoStore) behind the uniform device contract (build.md §8).
//
// It exists so hardware emulation lives in a single place rather than being duplicated inside each
// per-family Go adapter. When the hardware-emulator flag is ON, flow-orchestrator routes every
// device task here instead of to the real per-family adapters (which, in production, hold the
// actual protocol drivers). The emulator therefore always simulates — it never opens a socket and
// has no real-hardware path; flow only sends it traffic while the emulator is enabled.
//
// HTTP server exposing health/readiness probes, the device contract POST /tasks for every family,
// and GET /state for the simulated telemetry shown on the System info screen.
package main

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

const (
	serviceName = "equipment-emulator"
	transport   = "in-process simulator (all families)"
	defaultPort = "9097" // adapters own 9091/9093/9094/9096; sniffer 9095/9200
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

	// Seed the live, runtime-tunable config (simulated latency + fault rate) from env.
	initConfigFromEnv()

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
	mux.HandleFunc("/config", handleConfig)
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"service":   serviceName,
			"families":  supportedFamilies(),
			"transport": transport,
			"status":    "emulator",
			"version":   version,
			"commit":    commit,
			"buildTime": buildTime,
		})
	})

	srv := &http.Server{Addr: ":" + port, Handler: mux}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go telemetryLoop(ctx)

	go func() {
		log.Printf("%s listening on :%s (families=%v)", serviceName, port, supportedFamilies())
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

// telemetryLoop advances the simulated telemetry snapshot and emits a heartbeat, mirroring the
// per-adapter deviceLoop so the System info screen sees a live emulator.
func telemetryLoop(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			ticks, completed, failed := sim.tick(time.Now())
			log.Printf("%s: emulator heartbeat ticks=%d completed=%d failed=%d", serviceName, ticks, completed, failed)
		}
	}
}
