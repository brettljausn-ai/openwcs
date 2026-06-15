package org.openwcs.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the full Spring context with the REAL beans (no mocks) so a wiring failure fails the build.
 * Regression guard for the startup crash where {@code AnthropicChatModel} had two constructors and
 * neither was {@code @Autowired}, so Spring fell back to a missing no-arg constructor and the service
 * crash-looped. The other tests {@code @MockBean} {@code ChatModel}, which masked this.
 */
@SpringBootTest
class ContextLoadsTest {

    @Test
    void contextLoads() {
        // If the application context can't be built (e.g. an unresolvable constructor), this fails.
    }
}
