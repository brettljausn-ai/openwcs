package org.openwcs.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

/**
 * Reads a service container's recent logs via the Docker Engine API over the mounted
 * {@code /var/run/docker.sock} (see docker-compose.yml — the gateway is the only container with the
 * socket). Containers are matched by their Compose service label, so this is independent of the
 * project name / container-id. Blocking by nature — callers run it on a worker scheduler.
 *
 * <p>Privileged: socket access is root-equivalent on the host. Acceptable for the single-box demo
 * (the endpoint is behind edge auth and the ADMIN-only System info screen); not for a hardened prod.
 */
@Component
public class SystemLogsService {

    private final String dockerHost;
    private volatile DockerClient client;

    public SystemLogsService(
            @Value("${openwcs.system.docker-host:unix:///var/run/docker.sock}") String dockerHost) {
        this.dockerHost = dockerHost;
    }

    private DockerClient client() {
        DockerClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    DockerClientConfig cfg = DefaultDockerClientConfig.createDefaultConfigBuilder()
                            .withDockerHost(dockerHost)
                            .build();
                    DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                            .dockerHost(cfg.getDockerHost())
                            .maxConnections(10)
                            .build();
                    c = DockerClientImpl.getInstance(cfg, http);
                    client = c;
                }
            }
        }
        return c;
    }

    /**
     * The last {@code tail} log lines (stdout + stderr, timestamped) of the container running the
     * given Compose service. Throws if no such container exists or the socket is unreachable.
     */
    public String tail(String composeService, int tail) {
        DockerClient c = client();
        List<Container> found = c.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("com.docker.compose.service", composeService))
                .exec();
        if (found.isEmpty()) {
            throw new NoSuchElementException("No container found for service '" + composeService + "'");
        }
        String containerId = found.get(0).getId();
        StringBuilder sb = new StringBuilder();
        try {
            c.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail)
                    .withTimestamps(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            sb.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return sb.toString();
    }
}
