package main

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestCsvDecoder(t *testing.T) {
	d := csvDecoder{}
	if ev, ok := d.Decode("NODE-1,HU123"); !ok || ev.Node != "NODE-1" || ev.Barcode != "HU123" {
		t.Fatalf("good telegram not decoded: %+v ok=%v", ev, ok)
	}
	if _, ok := d.Decode("garbage"); ok {
		t.Fatalf("malformed telegram should not decode")
	}
	if _, ok := d.Decode("NODE,"); ok {
		t.Fatalf("empty barcode should not decode")
	}
}

func TestIPAllowlist(t *testing.T) {
	open := newSniffer("", csvDecoder{}, nil)
	if !open.ipAllowed("10.0.0.9") {
		t.Fatalf("empty allowlist should accept any source")
	}
	restricted := newSniffer("10.0.0.1, 10.0.0.2", csvDecoder{}, nil)
	if !restricted.ipAllowed("10.0.0.2") || restricted.ipAllowed("10.0.0.9") {
		t.Fatalf("allowlist not enforced")
	}
}

type record struct {
	ev ScanEvent
	ip string
}

type chanForwarder struct{ ch chan record }

func (f chanForwarder) Forward(ev ScanEvent, sourceIP string) error {
	f.ch <- record{ev, sourceIP}
	return nil
}

func TestSnifferDecodesAndForwards(t *testing.T) {
	fwd := chanForwarder{ch: make(chan record, 8)}
	snf := newSniffer("", csvDecoder{}, fwd)

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go snf.serve(ln)

	conn, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	// Two valid telegrams and one malformed line (ignored).
	if _, err := conn.Write([]byte("NODE-1,HU1\nbad-line\nNODE-2,HU1\n")); err != nil {
		t.Fatal(err)
	}
	conn.Close()

	got := make([]record, 0, 2)
	for len(got) < 2 {
		select {
		case r := <-fwd.ch:
			got = append(got, r)
		case <-time.After(2 * time.Second):
			t.Fatalf("timed out; got %d of 2 forwards", len(got))
		}
	}
	if got[0].ev.Node != "NODE-1" || got[1].ev.Node != "NODE-2" {
		t.Fatalf("unexpected forwards: %+v", got)
	}
	if got[0].ip != "127.0.0.1" {
		t.Fatalf("sourceIP not captured: %q", got[0].ip)
	}
}

func TestHTTPForwarderPostsObservation(t *testing.T) {
	type obs struct {
		WarehouseID string `json:"warehouseId"`
		Node        string `json:"node"`
		Barcode     string `json:"barcode"`
		SourceIP    string `json:"sourceIp"`
	}
	received := make(chan obs, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		var o obs
		_ = json.Unmarshal(body, &o)
		received <- o
		w.WriteHeader(http.StatusAccepted)
	}))
	defer server.Close()

	f := newHTTPForwarder(server.URL, "wh-1")
	if err := f.Forward(ScanEvent{Node: "N9", Barcode: "HU9"}, "10.0.0.5"); err != nil {
		t.Fatal(err)
	}
	select {
	case o := <-received:
		if o.WarehouseID != "wh-1" || o.Node != "N9" || o.Barcode != "HU9" || o.SourceIP != "10.0.0.5" {
			t.Fatalf("unexpected observation: %+v", o)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("WCS did not receive the observation")
	}
}
