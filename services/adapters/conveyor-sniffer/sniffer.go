package main

import (
	"bufio"
	"log"
	"net"
	"strings"
)

// sniffer ingests newline-delimited vendor telegrams from the conveyor network — accepted only
// from the configured source IPs — decodes them, and forwards normalized scans to the WCS.
// (The connection feeding it is the capture front-end: a controller push stream today, a
// libpcap mirror-port tap as a drop-in source later.)
type sniffer struct {
	allowed   map[string]bool // empty ⇒ allow all
	decoder   Decoder
	forwarder Forwarder
}

func newSniffer(allowedCSV string, decoder Decoder, forwarder Forwarder) *sniffer {
	allowed := map[string]bool{}
	for _, ip := range strings.Split(allowedCSV, ",") {
		ip = strings.TrimSpace(ip)
		if ip != "" {
			allowed[ip] = true
		}
	}
	return &sniffer{allowed: allowed, decoder: decoder, forwarder: forwarder}
}

// ipAllowed reports whether telegrams from this source IP are accepted. An empty allowlist
// accepts any source (single-segment / dev).
func (s *sniffer) ipAllowed(ip string) bool {
	if len(s.allowed) == 0 {
		return true
	}
	return s.allowed[ip]
}

// serve accepts telegram connections until the listener is closed.
func (s *sniffer) serve(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go s.handle(conn)
	}
}

func (s *sniffer) handle(conn net.Conn) {
	defer conn.Close()
	ip, _, _ := net.SplitHostPort(conn.RemoteAddr().String())
	if !s.ipAllowed(ip) {
		log.Printf("conveyor-sniffer: rejecting telegram source %s (not in allowlist)", ip)
		return
	}
	scanner := bufio.NewScanner(conn)
	for scanner.Scan() {
		ev, ok := s.decoder.Decode(scanner.Text())
		if !ok {
			continue
		}
		if err := s.forwarder.Forward(ev, ip); err != nil {
			log.Printf("conveyor-sniffer: forward of %s@%s failed: %v", ev.Barcode, ev.Node, err)
		}
	}
}
