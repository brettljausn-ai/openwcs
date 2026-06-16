package org.openwcs.processdesigner;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full process-designer context against PostgreSQL 16 (Flyway V1 then Hibernate
 * {@code ddl-auto=validate}), with MockMvc. Security is enabled so the RBAC filter is exercised;
 * tests pass X-Auth-Roles headers explicitly.
 *
 * <p>The container is a JVM-wide singleton (started once, never stopped by JUnit) so it survives
 * across the multiple Spring contexts the suite caches (e.g. the {@code @Import} variant) — a
 * per-class {@code @Container} would be torn down while a cached context still references it.
 */
@SpringBootTest(properties = "openwcs.security.enabled=true")
@AutoConfigureMockMvc
abstract class AbstractIntegrationTest {

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
}
