// Package main is the AutoStore device adapter.
//
// Transport: REST (grid controller).
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
	serviceName = "autostore-adapter"
	family      = "AutoStore"
	transport   = "REST (grid controller)"
	defaultPort = "9094"
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
