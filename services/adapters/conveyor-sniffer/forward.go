package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"
)

// Forwarder posts a normalized scan to the WCS topology-learning endpoint. The source endpoint
// (IP and, when known, port) identifies the conveyor controller/PLC the scan came from, so the
// WCS can group nodes seen behind the same endpoint into a controller.
type Forwarder interface {
	Forward(ev ScanEvent, sourceIP, sourcePort string) error
}

// httpForwarder POSTs to flow-orchestrator's POST /api/flow/conveyor/observations.
type httpForwarder struct {
	url         string
	warehouseID string
	client      *http.Client
}

func newHTTPForwarder(url, warehouseID string) *httpForwarder {
	return &httpForwarder{url: url, warehouseID: warehouseID, client: &http.Client{Timeout: 5 * time.Second}}
}

func (f *httpForwarder) Forward(ev ScanEvent, sourceIP, sourcePort string) error {
	payload := map[string]any{
		"warehouseId": f.warehouseID,
		"node":        ev.Node,
		"barcode":     ev.Barcode,
		"sourceIp":    sourceIP,
	}
	// Port is optional; include it as a number only when it parses, so the WCS can group nodes
	// by ip:port into a controller.
	if p, err := strconv.Atoi(sourcePort); err == nil {
		payload["sourcePort"] = p
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, f.url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return fmt.Errorf("WCS observations endpoint returned %d", resp.StatusCode)
	}
	return nil
}
