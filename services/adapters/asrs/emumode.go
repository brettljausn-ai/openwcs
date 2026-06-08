package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"sync/atomic"
	"time"
)

// emumode.go is the hardware-emulator flag poller. It is duplicated per adapter module (like
// dailylog.go) because each adapter is its own Go module and we keep to stdlib only.
//
// The flag is owned by master-data: every pollInterval the adapter GETs
// {WCS_MASTER_DATA_URL}/api/master-data/emulator and reads {"enabled": bool}. On any error the
// last known value is kept. The default is OFF, so a fresh adapter that can't reach master-data
// behaves as "no live hardware" rather than silently simulating.
const (
	emulatorPollInterval = 10 * time.Second
	defaultMasterDataURL = "http://master-data:8081"
)

// emulatorEnabled holds the current flag (0 = OFF, 1 = ON). Guarded atomically so the poller and
// request handlers can read/write it without a mutex.
var emulatorEnabled int32

// masterDataURL returns the base URL of the master-data service the flag is polled from.
func masterDataURL() string {
	if u := os.Getenv("WCS_MASTER_DATA_URL"); u != "" {
		return u
	}
	return defaultMasterDataURL
}

// EmulatorOn reports whether the hardware emulator is currently enabled.
func EmulatorOn() bool { return atomic.LoadInt32(&emulatorEnabled) == 1 }

// EmulatorMode returns "ON" or "OFF" for display in info/state payloads.
func EmulatorMode() string {
	if EmulatorOn() {
		return "ON"
	}
	return "OFF"
}

// setEmulator forces the flag; used by the poller and by tests.
func setEmulator(on bool) {
	var v int32
	if on {
		v = 1
	}
	atomic.StoreInt32(&emulatorEnabled, v)
}

// emulatorFlag is the shape of the master-data emulator endpoint response.
type emulatorFlag struct {
	Enabled bool `json:"enabled"`
}

// fetchEmulatorFlag GETs the flag once. It returns the parsed value or an error (caller keeps the
// last known value on error).
func fetchEmulatorFlag(ctx context.Context, client *http.Client) (bool, error) {
	url := masterDataURL() + "/api/master-data/emulator"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return false, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	var flag emulatorFlag
	if err := json.NewDecoder(resp.Body).Decode(&flag); err != nil {
		return false, err
	}
	return flag.Enabled, nil
}

// StartEmulatorPoller polls the master-data emulator flag every emulatorPollInterval until ctx is
// done. It does an immediate poll on start so the adapter doesn't wait a full interval to learn
// the mode. Errors are logged at most as a single line and the last known value is retained.
func StartEmulatorPoller(ctx context.Context) {
	client := &http.Client{Timeout: 5 * time.Second}
	poll := func() {
		on, err := fetchEmulatorFlag(ctx, client)
		if err != nil {
			return // keep last known value
		}
		if on != EmulatorOn() {
			setEmulator(on)
			log.Printf("%s: emulator mode -> %s", serviceName, EmulatorMode())
		}
	}
	go func() {
		poll()
		ticker := time.NewTicker(emulatorPollInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				poll()
			}
		}
	}()
}
