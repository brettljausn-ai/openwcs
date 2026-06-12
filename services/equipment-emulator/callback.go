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
		log.Printf("%s: WARNING task %s result callback not sent: marshal error %v; flow keeps the task DISPATCHED until resolved", serviceName, taskID, err)
		return
	}
	resp, err := callbackClient.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		log.Printf("%s: WARNING task %s result callback POST %s failed: %v; result %q (%s) is lost, flow keeps the task DISPATCHED until resolved",
			serviceName, taskID, url, err, res.Status, res.Detail)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		log.Printf("%s: WARNING task %s result callback %s returned HTTP %d; result %q (%s) not accepted, flow keeps the task DISPATCHED until resolved",
			serviceName, taskID, url, resp.StatusCode, res.Status, res.Detail)
		return
	}
	log.Printf("%s: task %s result %s (%s) posted back to flow", serviceName, taskID, res.Status, res.Detail)
}
