package org.openwcs.integration.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for integration-host web tests: boots the context against PostgreSQL 16 (the service now
 * has a small store for idempotency keys + webhook state) with MockMvc. Subclasses add their
 * {@code @MockBean} clients and test methods.
 *
 * <p>Uses a <b>singleton</b> container (started once, never stopped) rather than the
 * {@code @Testcontainers} per-class lifecycle: several test classes here share an identical
 * {@code @MockBean} set, so Spring caches one context across them — a per-class container would
 * be stopped after the first class, leaving the cached context's datasource pointing at a dead
 * DB. The singleton stays up for the whole run; Ryuk reaps it at JVM exit.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractHostIntegrationTest {

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

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper om;
}
