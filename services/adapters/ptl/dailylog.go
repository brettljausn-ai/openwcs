package main

import (
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// dailyWriter writes to a per-day file under ${OPENWCS_LOG_DIR}/<service>/<service>.<date>.log,
// rolling at midnight (local date) and pruning files older than logRetentionDays. It's safe for
// concurrent use. Any I/O error degrades to a no-op write (stdout logging is unaffected).
const logRetentionDays = 14

type dailyWriter struct {
	mu      sync.Mutex
	dir     string
	service string
	day     string
	file    *os.File
}

func logDir() string {
	if d := os.Getenv("OPENWCS_LOG_DIR"); d != "" {
		return d
	}
	return "/var/log/openwcs"
}

// newDailyLog returns a writer for the service's daily log file, or nil if the log directory
// can't be created (caller then logs to stdout only).
func newDailyLog(service string) io.Writer {
	dir := filepath.Join(logDir(), service)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil
	}
	w := &dailyWriter{dir: dir, service: service}
	if err := w.roll(time.Now()); err != nil {
		return nil
	}
	return w
}

func (w *dailyWriter) roll(now time.Time) error {
	day := now.Format("2006-01-02")
	if w.file != nil && day == w.day {
		return nil
	}
	if w.file != nil {
		_ = w.file.Close()
	}
	f, err := os.OpenFile(filepath.Join(w.dir, w.service+"."+day+".log"),
		os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		w.file = nil
		return err
	}
	w.file = f
	w.day = day
	w.prune(now)
	return nil
}

func (w *dailyWriter) prune(now time.Time) {
	entries, err := os.ReadDir(w.dir)
	if err != nil {
		return
	}
	cutoff := now.AddDate(0, 0, -logRetentionDays)
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			continue
		}
		if info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(w.dir, e.Name()))
		}
	}
}

func (w *dailyWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if err := w.roll(time.Now()); err != nil || w.file == nil {
		return len(p), nil // degrade gracefully; stdout still gets the line
	}
	return w.file.Write(p)
}
