package org.openwcs.allocation.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openwcs.allocation.api.BatchingResult;
import org.openwcs.allocation.api.PickBatchView;
import org.openwcs.allocation.client.MasterDataClient;
import org.openwcs.allocation.domain.AllocationLine;
import org.openwcs.allocation.domain.BatchMember;
import org.openwcs.allocation.domain.OrderAllocation;
import org.openwcs.allocation.domain.Pick;
import org.openwcs.allocation.domain.PickBatch;
import org.openwcs.allocation.repo.OrderAllocationRepository;
import org.openwcs.allocation.repo.PickBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds pick batches (ADR 0002 §6). Eligible orders (FULFILLABLE, total pieces ≤
 * {@code batchMaxPieces}) are grouped — up to {@code batchMaxOrders} per pick tote — into
 * batches with one combined pick list and a per-member separation plan.
 */
@Service
public class BatchingService {

    private final OrderAllocationRepository allocations;
    private final PickBatchRepository batches;
    private final MasterDataClient masterData;

    public BatchingService(OrderAllocationRepository allocations,
                           PickBatchRepository batches,
                           MasterDataClient masterData) {
        this.allocations = allocations;
        this.batches = batches;
        this.masterData = masterData;
    }

    @Transactional
    public BatchingResult buildBatches(UUID warehouseId, List<String> orderRefs) {
        MasterDataClient.FulfillmentConfig config = masterData.fulfillmentConfig(warehouseId);
        if (!config.batchEnabled()) {
            throw new IllegalStateException("Batch picking is disabled for warehouse " + warehouseId);
        }

        List<OrderAllocation> eligible = new ArrayList<>();
        List<String> notBatched = new ArrayList<>();
        for (String orderRef : orderRefs) {
            OrderAllocation allocation = allocations.findByOrderRef(orderRef).orElse(null);
            if (allocation == null
                    || !"FULFILLABLE".equals(allocation.getStatus())
                    || totalPieces(allocation) > config.batchMaxPieces()) {
                notBatched.add(orderRef);
            } else {
                eligible.add(allocation);
            }
        }

        int perTote = Math.max(1, config.batchMaxOrders());
        List<PickBatchView> created = new ArrayList<>();
        for (int i = 0; i < eligible.size(); i += perTote) {
            List<OrderAllocation> group = eligible.subList(i, Math.min(i + perTote, eligible.size()));
            created.add(PickBatchView.from(buildBatch(warehouseId, config.pickToteShipperId(), group)));
        }
        return new BatchingResult(created, notBatched);
    }

    private PickBatch buildBatch(UUID warehouseId, UUID pickToteShipperId, List<OrderAllocation> group) {
        PickBatch batch = new PickBatch();
        batch.setWarehouseId(warehouseId);
        batch.setPickToteShipperId(pickToteShipperId);
        batch.setStatus("OPEN");

        List<BatchMember> members = new ArrayList<>();
        List<BatchPlanner.PickItem> items = new ArrayList<>();
        int position = 1;
        for (OrderAllocation allocation : group) {
            members.add(new BatchMember(allocation.getOrderRef(), position++, allocation.getShippers()));
            for (AllocationLine line : allocation.getLines()) {
                for (Pick pick : line.getPicks()) {
                    items.add(new BatchPlanner.PickItem(
                            pick.locationId(), line.getSkuId(), pick.qty(), pick.reservationId()));
                }
            }
        }
        batch.setMembers(members);
        batch.setPickLines(BatchPlanner.merge(items));
        return batches.save(batch);
    }

    @Transactional(readOnly = true)
    public PickBatchView get(UUID batchId) {
        return PickBatchView.from(batches.findById(batchId)
                .orElseThrow(() -> new org.openwcs.allocation.api.AllocationNotFoundException("batch " + batchId)));
    }

    private static long totalPieces(OrderAllocation allocation) {
        return allocation.getLines().stream()
                .map(AllocationLine::getAllocatedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .longValue();
    }
}
