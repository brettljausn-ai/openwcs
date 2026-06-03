package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.DeviceTaskNotFoundException;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.RequestDeviceTask;
import org.openwcs.flow.client.DeviceClient;
import org.openwcs.flow.service.DeviceTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClientException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the flow-orchestrator against PostgreSQL 16 with the device adapter mocked. Verifies
 * the task lifecycle: a successful dispatch records COMPLETED, an adapter error records FAILED
 * (without losing the task), and tasks are queryable by id and correlation.
 */
@SpringBootTest
@Testcontainers
class DeviceTaskServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    DeviceClient deviceClient;

    @Autowired
    DeviceTaskService service;

    @Test
    void successfulDispatchRecordsCompleted() {
        when(deviceClient.execute(any())).thenReturn(
                new DeviceClient.DeviceResult("COMPLETED", "conveyed", Map.of("simulated", true)));

        UUID correlationId = UUID.randomUUID();
        RequestDeviceTask request = new RequestDeviceTask(
                UUID.randomUUID(), "CONVEYOR", UUID.randomUUID(), "CONVEY",
                Map.of("to", "P10"), correlationId);

        DeviceTaskView view = service.request(request, "tester");
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.detail()).isEqualTo("conveyed");
        assertThat(view.result()).containsEntry("simulated", true);

        assertThat(service.get(view.id()).status()).isEqualTo("COMPLETED");
        List<DeviceTaskView> byCorrelation = service.byCorrelation(correlationId);
        assertThat(byCorrelation).hasSize(1);
        assertThat(byCorrelation.get(0).id()).isEqualTo(view.id());
    }

    @Test
    void adapterErrorRecordsFailed() {
        when(deviceClient.execute(any())).thenThrow(new RestClientException("connection refused"));

        RequestDeviceTask request = new RequestDeviceTask(
                UUID.randomUUID(), "CONVEYOR", null, "CONVEY", Map.of(), null);

        DeviceTaskView view = service.request(request, "tester");
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.detail()).contains("adapter call failed");
        // The task is still persisted despite the failure.
        assertThat(service.get(view.id()).status()).isEqualTo("FAILED");
    }

    @Test
    void searchReturnsRecentTasksFilteredAndNewestFirst() {
        when(deviceClient.execute(any())).thenReturn(
                new DeviceClient.DeviceResult("COMPLETED", "ok", Map.of()));

        UUID warehouse = UUID.randomUUID();
        UUID otherWarehouse = UUID.randomUUID();
        DeviceTaskView first = service.request(
                new RequestDeviceTask(warehouse, "CONVEYOR", null, "CONVEY", Map.of(), null), "tester");
        DeviceTaskView second = service.request(
                new RequestDeviceTask(warehouse, "ASRS", null, "STORE", Map.of(), null), "tester");
        service.request(
                new RequestDeviceTask(otherWarehouse, "CONVEYOR", null, "CONVEY", Map.of(), null), "tester");

        // Filter by warehouse: the two warehouse tasks, newest first.
        List<DeviceTaskView> byWarehouse = service.search(warehouse, null, null, null, 100);
        assertThat(byWarehouse).extracting(DeviceTaskView::id)
                .containsExactly(second.id(), first.id());

        // Filter by family within the warehouse: only the CONVEYOR one.
        List<DeviceTaskView> byFamily = service.search(warehouse, null, "CONVEYOR", null, 100);
        assertThat(byFamily).extracting(DeviceTaskView::id).containsExactly(first.id());

        // Filter by status within the warehouse: both warehouse tasks are COMPLETED.
        // (Scoped to the warehouse so other tests' tasks in the shared DB don't leak in.)
        assertThat(service.search(warehouse, "COMPLETED", null, null, 100))
                .extracting(DeviceTaskView::id)
                .containsExactly(second.id(), first.id());
        assertThat(service.search(warehouse, "FAILED", null, null, 100)).isEmpty();

        // Limit is honoured.
        assertThat(service.search(warehouse, null, null, null, 1))
                .extracting(DeviceTaskView::id)
                .containsExactly(second.id());
    }

    @Test
    void unknownTaskIdThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(
                DeviceTaskNotFoundException.class, () -> service.get(UUID.randomUUID()));
    }
}
