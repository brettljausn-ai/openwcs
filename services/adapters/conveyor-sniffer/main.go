// Package main is the Conveyor Sniffer adapter.
//
// It listens for vendor scan telegrams from the conveyor network — accepted only from the
// configured source IPs — decodes them into normalized scans, and forwards them to the WCS
// topology-learning endpoint (POST /api/flow/conveyor/observations), which infers the topology
// for an admin to confirm. The capture front-end is a controller push stream over TCP today; a
// libpcap mirror-port tap can replace it as a drop-in source (same Decoder/Forwarder seam).
package main

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

const (
	serviceName = "conveyor-sniffer"
	defaultPort = "9095" // health/info
	defaultTcp  = ":9200" // telegram ingest
)

// Build metadata, injected via -ldflags at build time (see Dockerfile). Defaults cover `go run`.
var (
	version   = "0.1.0-SNAPSHOT"
	commit    = "dev"
	buildTime = "unknown"
)

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func main() {
	// Mirror logs to a daily-rotated file for the System info screen (kept ~14 days). Stdout is
	// unaffected, so `docker logs` keeps working.
	if fw := newDailyLog(serviceName); fw != nil {
		log.SetOutput(io.MultiWriter(os.Stdout, fw))
	}

	port := env("PORT", defaultPort)
	tcpAddr := env("SNIFFER_LISTEN", defaultTcp)
	wcsURL := env("WCS_OBSERVATIONS_URL", "http://localhost:8085/api/flow/conveyor/observations")
	warehouseID := os.Getenv("WAREHOUSE_ID")
	allowed := os.Getenv("ALLOWED_IPS") // CSV; empty = accept any source
	decoderName := env("DECODER", "csv")

	if warehouseID == "" {
		log.Printf("%s: WARNING WAREHOUSE_ID is unset; observations will omit the warehouse id", serviceName)
	}

	snf := newSniffer(allowed, decoderByName(decoderName), newHTTPForwarder(wcsURL, warehouseID))

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Telegram ingest listener.
	ln, err := net.Listen("tcp", tcpAddr)
	if err != nil {
		log.Fatalf("%s: cannot listen on %s: %v", serviceName, tcpAddr, err)
	}
	go snf.serve(ln)
	log.Printf("%s: ingesting telegrams on %s (decoder=%s, allow=%q) -> %s", serviceName, tcpAddr, decoderName,
		allowed, wcsURL)

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("ok")) })
	mux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("ready")) })
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{
			"service":     serviceName,
			"description": "Captures conveyor scan telegrams from defined IPs and posts them to the WCS for topology learning.",
			"ingest":      tcpAddr,
			"status":      "skeleton",
			"version":     version,
			"commit":      commit,
			"buildTime":   buildTime,
		})
	})
	srv := &http.Server{Addr: ":" + port, Handler: mux}
	go func() {
		log.Printf("%s health listening on :%s", serviceName, port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http server error: %v", err)
		}
	}()

	<-ctx.Done()
	log.Printf("%s shutting down", serviceName)
	_ = ln.Close()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutdownCtx)
}
