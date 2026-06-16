package org.openwcs.processdesigner;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Starts the full context with the REAL beans (no mocks) and NO Anthropic key, so a wiring failure
 * fails the build. Regression guard mirroring the assistant service: the {@code AnthropicTaskAssistModel}
 * has a no-arg {@code @Autowired} constructor and a test-seam constructor, and {@code AiAssistConfig}
 * resolves a missing key gracefully (no unresolvable constructor), so the context must start even when
 * AI assist is unconfigured (it only 503s on use).
 */
@SpringBootTest
class ContextLoadsTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void contextLoads() {
        // Fails if the context can't be built (e.g. an unresolvable Anthropic/sandbox bean).
    }
}
