package main

import (
	"bytes"
	"encoding/json"
	"log"
	"net/http"
	"time"
)

// callback.go posts an asynchronous task's terminal result back to flow-orchestrator (the URL flow
// supplied in the task's callbackUrl). Best-effort: a failed callback is logged, not retried — flow
// keeps the task DISPATCHED, which is the honest state.

var callbackClient = &http.Client{Timeout: 5 * time.Second}

func postCallback(url, taskID string, res deviceTaskResult) {
	body, err := json.Marshal(res)
	if err != nil {
		log.Printf("%s: task %s callback marshal error: %v", serviceName, taskID, err)
		return
	}
	resp, err := callbackClient.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		log.Printf("%s: task %s callback POST %s failed: %v", serviceName, taskID, url, err)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		log.Printf("%s: task %s callback %s returned HTTP %d", serviceName, taskID, url, resp.StatusCode)
		return
	}
	log.Printf("%s: task %s result (%s) posted back to flow", serviceName, taskID, res.Status)
}
