package main

import (
	"sync"
	"sync/atomic"
	"time"
)

// handover.go models a physical truth the emulator was missing: an ASRS (and an AutoStore grid)
// moves ONE tote at a time through its shuttle -> lift -> handover point. So consecutive tote moves
// on the same device must be serialised and spaced, and totes leaving storage arrive at the
// conveyor staggered, never two at once.
//
// Without this, every task ran in its own goroutine with no per-device ordering: two retrievals
// dispatched together (a common case) completed at the same instant, so both CONVEY legs started
// together and the two totes entered the conveyor on the same spot — rendering as a single tote on
// the live twin until the pick station separated them. Serialising the handover fixes that at the
// source (the physics), instead of de-stacking visually after the fact.

// asrsHandoverMs is the minimum gap (ms) between consecutive tote deliveries out of / into one ASRS
// device — the shuttle/lift/handover throughput. At the default 0.5 m/s conveyor speed, 1800 ms is
// ~0.9 m of belt between totes, a clear nose-to-tail gap.
var asrsHandoverMs atomic.Int64

const defaultAsrsHandoverMs = 1800

func asrsHandover() time.Duration {
	return time.Duration(asrsHandoverMs.Load()) * time.Millisecond
}

// handoverGate tracks, per equipment id, the earliest time the next tote may begin its move through
// the shared shuttle/lift/handover. It is the one-tote-at-a-time resource of an ASRS aisle.
type handoverGate struct {
	mu       sync.Mutex
	nextFree map[string]time.Time
}

func newHandoverGate() *handoverGate {
	return &handoverGate{nextFree: map[string]time.Time{}}
}

// reserve books the shared handover of `equip` for `occupancy` and returns how long this tote must
// wait before its move may begin (zero when the device is idle). Reservations are FIFO by arrival,
// so totes leave the ASRS in dispatch order, one per `occupancy` window.
func (g *handoverGate) reserve(equip string, occupancy time.Duration) time.Duration {
	g.mu.Lock()
	defer g.mu.Unlock()
	now := time.Now()
	start := now
	if t, ok := g.nextFree[equip]; ok && t.After(start) {
		start = t
	}
	g.nextFree[equip] = start.Add(occupancy)
	return start.Sub(now)
}

// handover is the process-wide gate; the emulator simulates one warehouse's devices per instance.
var handover = newHandoverGate()

// handoverCommands are the tote-moving ASRS / AutoStore commands that traverse the shared
// shuttle/lift/handover and therefore cannot run concurrently on one device. SCAN is instant and
// excluded.
var handoverCommands = map[string]map[string]bool{
	"ASRS":      {"STORE": true, "RETRIEVE": true, "RELOCATE": true},
	"AUTOSTORE": {"BIN_STORE": true, "BIN_RETRIEVE": true, "BIN_RELOCATE": true},
}

// usesHandover reports whether a family+command occupies the single-tote shuttle/lift/handover.
func usesHandover(family, command string) bool {
	return handoverCommands[family][command]
}
