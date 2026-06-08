// Package main is the AMR Geek+ device adapter.
//
// Transport: REST + WebSocket (RCS fleet manager).
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
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

const (
	serviceName = "amr-geekplus-adapter"
	family      = "AMR Geek+"
	transport   = "REST + WebSocket (RCS fleet manager)"
	defaultPort = "9093"
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
			"emulator":  EmulatorMode(),
			"version":   version,
			"commit":    commit,
			"buildTime": buildTime,
		})
	})

	srv := &http.Server{Addr: ":" + port, Handler: mux}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Poll the master-data emulator flag so /tasks and deviceLoop know whether to simulate.
	StartEmulatorPoller(ctx)

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

func deviceLoop(ctx context.Context) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if EmulatorOn() {
				// Emulator ON: advance simulated telemetry and emit a heartbeat. No socket is
				// ever opened in this mode.
				ticks, throughput, faults := sim.tick(time.Now())
				log.Printf("%s: emulator heartbeat ticks=%d throughput=%d faults=%d", serviceName, ticks, throughput, faults)
				continue
			}
			// TODO: real hardware connection (emulator off): maintain the RCS REST/WebSocket
			// session, publish telemetry to Kafka, and reconcile in-flight jobs on reconnect.
			log.Printf("%s: device heartbeat (stub, emulator off)", serviceName)
		}
	}
}
