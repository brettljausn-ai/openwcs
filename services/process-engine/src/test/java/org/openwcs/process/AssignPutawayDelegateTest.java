package org.openwcs.process;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.Test;
import org.openwcs.process.client.SlottingClient;
import org.openwcs.process.delegate.AssignPutawayDelegate;

/** The goods-in put-away delegate calls slotting and writes the destination back as variables. */
class AssignPutawayDelegateTest {

    @Test
    void writesChosenLocationBackToProcessVariables() {
        SlottingClient slotting = mock(SlottingClient.class);
        DelegateExecution execution = mock(DelegateExecution.class);

        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID location = UUID.randomUUID();
        UUID block = UUID.randomUUID();

        when(execution.getVariable("warehouseId")).thenReturn(wh);
        when(execution.getVariable("skuId")).thenReturn(sku);
        when(slotting.assignPutaway(eq(wh), any(), eq(sku), any(), any(), any(), any()))
                .thenReturn(new SlottingClient.Putaway(location, block, "RESERVE"));

        new AssignPutawayDelegate(slotting).execute(execution);

        verify(execution).setVariable("targetLocationId", location);
        verify(execution).setVariable("putawayMode", "RESERVE");
        verify(execution).setVariable("putawayBlockId", block);
    }
}
