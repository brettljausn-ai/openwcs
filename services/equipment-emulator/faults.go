package main

import "sync/atomic"

// faults.go injects deterministic simulated equipment faults: when faultEvery > 0, every Nth task is
// failed. Deterministic (a counter, not randomness) so it's testable and reproducible. The counter
// only advances while fault injection is enabled, so toggling it on via /config is predictable.

var taskSeq atomic.Int64

// shouldFault reports whether the current task should be failed as a simulated fault.
func shouldFault() bool {
	n := faultEvery.Load()
	if n <= 0 {
		return false
	}
	return taskSeq.Add(1)%n == 0
}
