package org.openwcs.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.openwcs.process.client.AllocationClient;
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
 * Verifies the end-to-end outbound process: release the order, allocate + cube it (service task),
 * branch on the allocation status (exclusive gateway), and on the fulfillable path wait on an
 * operator pick/pack user task, then dispatch the conveyor move and assign its route. The
 * not-fulfillable path ends without dispatching.
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
    @MockBean AllocationClient allocationClient;
    @MockBean DeviceTaskClient deviceTaskClient;
    @MockBean RouteClient routeClient;

    @Autowired RuntimeService runtime;
    @Autowired TaskService taskService;
    @Autowired HistoryService historyService;

    @Test
    void fulfillableOrderReleasesAllocatesWaitsThenDispatchesAndRoutes() {
        UUID warehouse = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        String orderRef = "SO-1001";

        when(allocationClient.allocate(eq(orderRef), eq(warehouse), anyList()))
                .thenReturn(new AllocationClient.Allocation(orderRef, "FULFILLABLE", 2));

        ProcessInstance instance = runtime.startProcessInstanceByKey("outbound", Map.of(
                "orderId", orderId.toString(),
                "orderRef", orderRef,
                "warehouseId", warehouse.toString(),
                "barcode", "HU-1",
                "targets", List.of("PICK", "PACK", "SHIP"),
                "lines", List.of(Map.of("lineNo", 1, "skuId", skuId.toString(), "qty", new BigDecimal("3"))),
                "family", "CONVEYOR",
                "command", "CONVEY"));

        // Released and allocated, then parked on the operator user task (not ended).
        verify(orderClient).release(orderId);
        verify(allocationClient).allocate(eq(orderRef), eq(warehouse), anyList());
        assertThat(instance.isEnded()).isFalse();

        List<Task> tasks = taskService.createTaskQuery().processInstanceId(instance.getId()).list();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getName()).isEqualTo("Confirm pick complete");

        // Operator confirms -> dispatch conveyor move, assign route, end.
        taskService.complete(tasks.get(0).getId());

        verify(deviceTaskClient).dispatch(eq(warehouse), eq("CONVEYOR"), any(), eq("CONVEY"), any(), any());
        verify(routeClient).assignRoute(eq(warehouse), eq("HU-1"), eq(List.of("PICK", "PACK", "SHIP")));
        assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
    }

    @Test
    void notFulfillableOrderEndsWithoutDispatching() {
        UUID warehouse = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        String orderRef = "SO-2002";

        when(allocationClient.allocate(anyString(), any(), anyList()))
                .thenReturn(new AllocationClient.Allocation(orderRef, "NOT_FULFILLABLE", 0));

        ProcessInstance instance = runtime.startProcessInstanceByKey("outbound", Map.of(
                "orderId", orderId.toString(),
                "orderRef", orderRef,
                "warehouseId", warehouse.toString(),
                "lines", List.of(Map.of("lineNo", 1, "skuId", skuId.toString(), "qty", new BigDecimal("3"))),
                "family", "CONVEYOR",
                "command", "CONVEY"));

        // Released + allocation attempted, but the gateway routes to the not-fulfillable end.
        verify(orderClient).release(orderId);
        verify(allocationClient).allocate(eq(orderRef), eq(warehouse), anyList());
        verify(deviceTaskClient, never()).dispatch(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(routeClient);

        assertThat(instance.isEnded()).isTrue();
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId()).count()).isZero();

        HistoricProcessInstance history = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult();
        assertThat(history.getEndActivityId()).isEqualTo("notFulfillable");
    }
}
