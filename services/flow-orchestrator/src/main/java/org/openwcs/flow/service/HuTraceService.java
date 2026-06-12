package org.openwcs.flow.service;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.api.HuTraceView;
import org.openwcs.flow.domain.HuTransportTrace;
import org.openwcs.flow.repo.HuTransportTraceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to and reads the per-HU transport trace (ADR-0007 §2.2 / §5). {@link #record} is the
 * single write path; rows are append-only and never updated. Each lifecycle transition in
 * {@link InductionQueueService} calls {@code record(...)} once per §5 write point.
 */
@Service
public class HuTraceService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HuTraceService.class);

    private final HuTransportTraceRepository traces;

    public HuTraceService(HuTransportTraceRepository traces) {
        this.traces = traces;
    }

    /**
     * Append one trace row. {@code correlationId} is set to {@code huId} (mirrors device_task
     * grouping). Called within the surrounding transition's transaction.
     */
    @Transactional
    public HuTransportTrace record(UUID warehouseId, UUID huId, String huCode, String point, String event,
                                   String decision, String fromPoint, String toPoint, UUID workplaceId,
                                   UUID taskId, UUID inductionEntryId) {
        HuTransportTrace t = new HuTransportTrace();
        t.setWarehouseId(warehouseId);
        t.setHuId(huId);
        t.setHuCode(huCode);
        t.setPoint(point);
        t.setEvent(event);
        t.setDecision(decision);
        t.setFromPoint(fromPoint);
        t.setToPoint(toPoint);
        t.setWorkplaceId(workplaceId);
        t.setCorrelationId(huId);
        t.setTaskId(taskId);
        t.setInductionEntryId(inductionEntryId);
        // Per-row trace writes stay DEBUG; the deciding transitions log themselves at INFO.
        log.debug("trace: hu {} {} at {} ({})", huCode != null ? huCode : huId, event, point, decision);
        return traces.save(t);
    }

    /** An HU's timeline, ts ASC. When {@code warehouseId} is given the lookup is scoped to it. */
    @Transactional(readOnly = true)
    public List<HuTraceView> timeline(UUID huId, UUID warehouseId) {
        List<HuTransportTrace> rows = warehouseId == null
                ? traces.findByHuIdOrderByTsAsc(huId)
                : traces.findByWarehouseIdAndHuIdOrderByTsAsc(warehouseId, huId);
        return rows.stream().map(HuTraceView::from).toList();
    }
}
