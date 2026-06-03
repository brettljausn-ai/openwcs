package main

import "strings"

// ScanEvent is a normalized "handling unit seen at a node" derived from a vendor telegram.
type ScanEvent struct {
	Node    string
	Barcode string
}

// Decoder turns one raw vendor telegram (a line) into a ScanEvent. Different conveyor vendors
// frame their scan telegrams differently; a Decoder isolates that per-vendor parsing so the
// rest of the sniffer (allowlist, forwarding) is vendor-neutral.
type Decoder interface {
	Decode(payload string) (ScanEvent, bool)
}

// csvDecoder parses a simple "NODE,BARCODE" telegram — a representative line protocol. Real
// vendor decoders (telegram framing, OPC-UA payloads, …) implement the same interface.
type csvDecoder struct{}

func (csvDecoder) Decode(payload string) (ScanEvent, bool) {
	parts := strings.SplitN(strings.TrimSpace(payload), ",", 2)
	if len(parts) != 2 {
		return ScanEvent{}, false
	}
	node := strings.TrimSpace(parts[0])
	barcode := strings.TrimSpace(parts[1])
	if node == "" || barcode == "" {
		return ScanEvent{}, false
	}
	return ScanEvent{Node: node, Barcode: barcode}, true
}

func decoderByName(name string) Decoder {
	switch name {
	case "csv", "":
		return csvDecoder{}
	default:
		return csvDecoder{}
	}
}
