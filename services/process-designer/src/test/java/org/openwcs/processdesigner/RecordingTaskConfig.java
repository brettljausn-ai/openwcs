package org.openwcs.processdesigner;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.openwcs.processdesigner.task.ProcessTask;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Registers a test-only curated task type {@code test.echo} that records what it received: the
 * resolved inputs, the operator's forwarded {@code X-Auth-User} (read from the in-scope request, as
 * the real RestClient identity-forwarding interceptor would), and an invocation count (to prove
 * idempotency). It echoes an output back so the engine's output-merge is observable.
 */
@TestConfiguration
public class RecordingTaskConfig {

    public static final class EchoTask implements ProcessTask {
        public final AtomicInteger invocations = new AtomicInteger();
        public volatile Map<String, Object> lastInputs;
        public volatile String lastAuthUser;

        @Override
        public String type() {
            return "test.echo";
        }

        @Override
        public Map<String, Object> execute(Map<String, Object> inputs) {
            invocations.incrementAndGet();
            this.lastInputs = inputs;
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                this.lastAuthUser = attrs.getRequest().getHeader("X-Auth-User");
            }
            Map<String, Object> out = new HashMap<>();
            out.put("echoed", inputs.get("in"));
            out.put("ok", true);
            return out;
        }
    }

    @Bean
    public EchoTask echoTask() {
        return new EchoTask();
    }
}
