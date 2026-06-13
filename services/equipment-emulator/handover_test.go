package main

import (
	"testing"
	"time"
)

// The shuttle/lift/handover is one-tote-at-a-time: consecutive reservations on the same device are
// serialised and spaced by the occupancy window, while a different device is independent.
func TestHandoverGateSerialisesAndSpaces(t *testing.T) {
	g := newHandoverGate()
	const occ = 100 * time.Millisecond
	const tol = 15 * time.Millisecond

	if w := g.reserve("ASRS-1", occ); w != 0 {
		t.Fatalf("first reserve on an idle device should not wait, got %v", w)
	}
	if w := g.reserve("ASRS-1", occ); w < occ-tol || w > occ+tol {
		t.Fatalf("second reserve should wait ~%v (one window), got %v", occ, w)
	}
	if w := g.reserve("ASRS-1", occ); w < 2*occ-tol {
		t.Fatalf("third reserve should wait ~%v (two windows), got %v", 2*occ, w)
	}
	if w := g.reserve("ASRS-2", occ); w != 0 {
		t.Fatalf("a different device has its own shuttle/lift and should not wait, got %v", w)
	}
}

// Only tote-moving ASRS / AutoStore commands occupy the shared handover; SCAN and conveyor/AMR work
// do not.
func TestUsesHandover(t *testing.T) {
	cases := []struct {
		family, command string
		want            bool
	}{
		{"ASRS", "RETRIEVE", true},
		{"ASRS", "STORE", true},
		{"ASRS", "RELOCATE", true},
		{"ASRS", "SCAN", false},
		{"AUTOSTORE", "BIN_RETRIEVE", true},
		{"AUTOSTORE", "BIN_STORE", true},
		{"CONVEYOR", "CONVEY", false},
		{"AMR", "TRANSPORT", false},
	}
	for _, c := range cases {
		if got := usesHandover(c.family, c.command); got != c.want {
			t.Errorf("usesHandover(%q, %q) = %v, want %v", c.family, c.command, got, c.want)
		}
	}
}
