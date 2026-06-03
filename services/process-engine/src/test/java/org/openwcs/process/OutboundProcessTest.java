package org.openwcs.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.openwcs.process.client.DeviceTaskClient;
import org.openwcs.process.client.OrderClient;
import org.openwcs.process.client.RouteClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the sample outbound process: release the order (service task), wait on a user task,
 * then dispatch the conveyor move (service task) — exercising delegates and a user/wait task.
 */
@SpringBootTest
@Testcontainers
class OutboundProcessTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean OrderClient orderClient;
    @MockBean DeviceTaskClient deviceTaskClient;
    @MockBean RouteClient routeClient;

    @Autowired RuntimeService runtime;
    @Autowired TaskService taskService;

    @Test
    void outboundReleasesWaitsForOperatorThenDispatches() {
        UUID warehouse = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ProcessInstance instance = runtime.startProcessInstanceByKey("outbound", Map.of(
                "orderId", orderId.toString(),
                "warehouseId", warehouse.toString(),
                "family", "CONVEYOR",
                "command", "CONVEY"));

        // Released, then parked on the user task (not ended).
        verify(orderClient).release(orderId);
        assertThat(instance.isEnded()).isFalse();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(instance.getId()).list();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getName()).isEqualTo("Confirm pick complete");

        // Operator confirms → process dispatches the conveyor move and ends.
        taskService.complete(tasks.get(0).getId());

        verify(deviceTaskClient).dispatch(eq(warehouse), eq("CONVEYOR"), any(), eq("CONVEY"), any(), any());
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
    }
}
