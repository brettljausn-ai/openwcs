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
	ip, port, _ := net.SplitHostPort(conn.RemoteAddr().String())
	if !s.ipAllowed(ip) {
		log.Printf("conveyor-sniffer: WARNING rejecting telegram source %s:%s (not in allowlist); connection dropped, scans from this controller are ignored", ip, port)
		return
	}
	log.Printf("conveyor-sniffer: telegram stream connected from controller %s:%s", ip, port)
	forwarded, undecodable, dropped := 0, 0, 0
	scanner := bufio.NewScanner(conn)
	for scanner.Scan() {
		raw := scanner.Text()
		ev, ok := s.decoder.Decode(raw)
		if !ok {
			undecodable++
			log.Printf("conveyor-sniffer: WARNING undecodable telegram from %s:%s dropped (no node/barcode pair parsed); raw line: %q", ip, port, raw)
			continue
		}
		if err := s.forwarder.Forward(ev, ip, port); err != nil {
			dropped++
			log.Printf("conveyor-sniffer: WARNING forward of barcode %s at node %s (source %s:%s) failed: %v; observation lost, topology learning misses this scan", ev.Barcode, ev.Node, ip, port, err)
			continue
		}
		forwarded++
		log.Printf("conveyor-sniffer: scan forwarded: barcode %s at node %s (controller %s:%s)", ev.Barcode, ev.Node, ip, port)
	}
	if err := scanner.Err(); err != nil {
		log.Printf("conveyor-sniffer: WARNING telegram stream from %s:%s broke: %v (%d scans forwarded, %d undecodable, %d forward failures this session)", ip, port, err, forwarded, undecodable, dropped)
		return
	}
	log.Printf("conveyor-sniffer: telegram stream from %s:%s closed by peer (%d scans forwarded, %d undecodable, %d forward failures this session)", ip, port, forwarded, undecodable, dropped)
}
