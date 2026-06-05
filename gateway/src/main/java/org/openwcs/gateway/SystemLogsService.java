package org.openwcs.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the daily-rotated per-service log files that every service + adapter writes to the shared
 * {@code openwcs-logs} volume (see libs/common logback-spring.xml and the Go adapters' dailylog.go).
 * Layout: {@code <logDir>/<service>/<service>.<yyyy-MM-dd>.log}. The gateway mounts the same volume
 * read-only. No Docker socket needed — logs survive container recreation and are kept ~14 days.
 */
@Component
public class SystemLogsService {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    // A service name only ever contains these; reject anything else outright (defence in depth on
    // top of the controller's registry check — and so a path segment can never be "..", "/", etc.).
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path logRoot;

    public SystemLogsService(
            @Value("${openwcs.system.log-dir:${OPENWCS_LOG_DIR:/var/log/openwcs}}") String logDir) {
        this.logRoot = Paths.get(logDir).toAbsolutePath().normalize();
    }

    /** Resolve a child of the log root, returning null if the input is unsafe or escapes the root. */
    private Path safeResolve(String first, String... more) {
        if (first == null || !SAFE_NAME.matcher(first).matches()) {
            return null;
        }
        Path p = logRoot.resolve(first);
        for (String m : more) {
            p = p.resolve(m);
        }
        p = p.normalize();
        return p.startsWith(logRoot) ? p : null;
    }

    /** Available log dates (yyyy-MM-dd) for a service, newest first. */
    public List<String> days(String service) {
        Path dir = safeResolve(service);
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        Pattern file = Pattern.compile(Pattern.quote(service) + "\\.(\\d{4}-\\d{2}-\\d{2})\\.log");
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString())
                    .map(file::matcher)
                    .filter(Matcher::matches)
                    .map(m -> m.group(1))
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The last {@code tail} lines of a service's log for {@code date} (yyyy-MM-dd); the most recent
     * available day when {@code date} is null/blank. Empty string when there's nothing to show.
     */
    public String tail(String service, String date, int tail) {
        String day = (date == null || date.isBlank()) ? null : date.trim();
        if (day == null) {
            List<String> available = days(service);
            if (available.isEmpty()) {
                return "";
            }
            day = available.get(0);
        }
        // Defend against path traversal — the date segment must be a plain yyyy-MM-dd.
        if (!DATE.matcher(day).matches()) {
            return "";
        }
        Path file = safeResolve(service, service + "." + day + ".log");
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - tail);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            return "";
        }
    }
}
