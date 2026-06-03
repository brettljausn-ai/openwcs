package org.openwcs.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.openwcs.process.client.DeviceTaskClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the Flowable-embedded process-engine against PostgreSQL 16 (the engine creates its ACT_*
 * tables). Verifies the sample goods-in process auto-deploys, and that starting an instance runs
 * the service task — originating a device task via the (mocked) delegate client — and completes.
 */
@SpringBootTest
@Testcontainers
class ProcessEngineTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    DeviceTaskClient deviceTasks;

    @Autowired
    RepositoryService repository;

    @Autowired
    RuntimeService runtime;

    @Test
    void sampleProcessIsDeployed() {
        assertThat(repository.createProcessDefinitionQuery().processDefinitionKey("goods-in").count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void startingGoodsInOriginatesADeviceTaskAndCompletes() {
        UUID warehouse = UUID.randomUUID();
        ProcessInstance instance = runtime.startProcessInstanceByKey("goods-in", Map.of(
                "warehouseId", warehouse.toString(),
                "family", "CONVEYOR",
                "command", "CONVEY"));

        // start → serviceTask → end runs synchronously, so the instance ends immediately.
        assertThat(instance.isEnded()).isTrue();
        verify(deviceTasks).dispatch(eq(warehouse), eq("CONVEYOR"), any(), eq("CONVEY"), any(), any());
    }
}
