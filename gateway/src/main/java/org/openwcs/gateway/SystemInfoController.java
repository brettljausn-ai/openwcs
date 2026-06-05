package org.openwcs.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Aggregates version + health for every backend service so the UI System info screen can show them
 * in one place. Lives ON the gateway (no route matches {@code /api/system/**}), reachable through
 * the same edge auth as the rest of {@code /api}. Each service is probed in parallel with a short
 * timeout; an unreachable service reports DOWN rather than failing the whole response.
 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private static final int MAX_TAIL = 2000;

    private final WebClient webClient;
    private final SystemServicesProperties props;
    private final SystemLogsService logsService;

    public SystemInfoController(
            WebClient.Builder builder, SystemServicesProperties props, SystemLogsService logsService) {
        this.webClient = builder.build();
        this.props = props;
        this.logsService = logsService;
    }

    /** One row per configured service: health status, version/commit/build time, probe latency. */
    public record ServiceStatus(
            String name,
            String kind,
            String status,
            String version,
            String commit,
            String buildTime,
            long latencyMs) {
    }

    @GetMapping("/services")
    public Mono<List<ServiceStatus>> services() {
        return Flux.fromIterable(props.getServices())
                .flatMap(this::probe)
                .collectList();
    }

    /**
     * Recent container logs for a known service (Docker socket). The name MUST be one of the
     * configured services — we never read an arbitrary container. Runs on a worker scheduler since
     * the Docker call is blocking; an unreachable socket / missing container returns an {@code error}.
     */
    @GetMapping("/services/{name}/logs")
    public Mono<Map<String, Object>> logs(
            @PathVariable String name, @RequestParam(defaultValue = "200") int tail) {
        boolean known = props.getServices().stream().anyMatch(s -> s.getName().equals(name));
        if (!known) {
            return Mono.just(Map.of("service", name, "tail", 0, "logs", "", "error", "unknown service"));
        }
        int t = Math.min(Math.max(tail, 1), MAX_TAIL);
        return Mono.fromCallable(() -> logsService.tail(name, t))
                .subscribeOn(Schedulers.boundedElastic())
                .map(text -> Map.<String, Object>of("service", name, "tail", t, "logs", text))
                .onErrorResume(e -> Mono.just(Map.of(
                        "service", name,
                        "tail", t,
                        "logs", "",
                        "error", e.getMessage() == null ? e.toString() : e.getMessage())));
    }

    private Mono<ServiceStatus> probe(SystemServicesProperties.Service svc) {
        boolean java = "java".equalsIgnoreCase(svc.getKind());
        String base = svc.getUri();

        return Mono.defer(() -> {
            long start = System.nanoTime();

            Mono<String> health = java
                    ? webClient.get().uri(base + "/actuator/health").retrieve().bodyToMono(Map.class)
                            .map(m -> str(m.get("status"), "UNKNOWN"))
                    : webClient.get().uri(base + "/healthz").retrieve().toBodilessEntity()
                            .map(r -> r.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN");

            @SuppressWarnings("unchecked")
            Mono<Map<String, Object>> info = webClient.get().uri(base + (java ? "/actuator/info" : "/"))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(m -> (Map<String, Object>) m)
                    .onErrorReturn(Map.of());

            return Mono.zip(
                            health.timeout(PROBE_TIMEOUT).onErrorReturn("DOWN"),
                            info.timeout(PROBE_TIMEOUT).onErrorReturn(Map.of()))
                    .map(t -> {
                        Map<String, Object> body = t.getT2();
                        String version;
                        String commit;
                        String buildTime;
                        if (java) {
                            Object b = body.get("build");
                            Map<?, ?> build = b instanceof Map ? (Map<?, ?>) b : Map.of();
                            version = str(build.get("version"), "");
                            commit = str(build.get("commit"), "");
                            buildTime = str(build.get("time"), "");
                        } else {
                            version = str(body.get("version"), "");
                            commit = str(body.get("commit"), "");
                            buildTime = str(body.get("buildTime"), "");
                        }
                        long ms = (System.nanoTime() - start) / 1_000_000;
                        return new ServiceStatus(
                                svc.getName(), svc.getKind(), t.getT1(), version, commit, buildTime, ms);
                    });
        });
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }
}
