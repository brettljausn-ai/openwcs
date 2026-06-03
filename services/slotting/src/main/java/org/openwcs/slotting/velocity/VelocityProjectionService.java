package org.openwcs.slotting.velocity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import org.openwcs.common.EventEnvelope;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.domain.VelocityOffset;
import org.openwcs.slotting.domain.VelocityProcessedEvent;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.openwcs.slotting.repo.VelocityOffsetRepository;
import org.openwcs.slotting.repo.VelocityProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts pick/outbound movements off the streamed transaction log into a per-(warehouse, SKU)
 * pending-pick tally (build.md §9). Mirrors {@code inventory}'s stock projection: each event is
 * applied exactly once — the {@code velocity_processed_event} inbox guards redelivery/replay —
 * and the {@code velocity_offset} cursor advances so progress is observable.
 *
 * <p>The learner separates <em>counting</em> (here, cheap, per event) from <em>decay + folding
 * into the EWMA score</em> (done by {@link VelocityClassifier} at recompute time). Each event
 * therefore just increments {@code pending_picks} and stamps {@code last_pick_at}; the recency
 * weighting is applied when the score is next decayed.
 */
@Service
public class VelocityProjectionService {

    private static final Logger log = LoggerFactory.getLogger(VelocityProjectionService.class);

    /** Cursor key in {@code velocity_offset}. */
    static final String VELOCITY_PROJECTION = "sku-velocity";

    private final SkuVelocityRepository velocity;
    private final VelocityProcessedEventRepository processedEvents;
    private final VelocityOffsetRepository offsets;
    private final ObjectMapper objectMapper;

    public VelocityProjectionService(SkuVelocityRepository velocity,
                                     VelocityProcessedEventRepository processedEvents,
                                     VelocityOffsetRepository offsets,
                                     ObjectMapper objectMapper) {
        this.velocity = velocity;
        this.processedEvents = processedEvents;
        this.offsets = offsets;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(EventEnvelope env) {
        String type = env.eventType();
        if (!VelocityEventTypes.OUTBOUND_EVENTS.contains(type)) {
            // Not an outbound movement — still track consumption progress so the cursor is honest.
            advanceOffset(env);
            return;
        }
        if (processedEvents.existsById(env.eventId())) {
            log.debug("Skipping already-counted velocity event {} ({})", env.eventId(), type);
            return;
        }

        VelocityMovementPayload p = objectMapper.convertValue(env.payload(), VelocityMovementPayload.class);
        if (p.warehouseId() == null || p.skuId() == null) {
            log.warn("Outbound event {} ({}) missing warehouseId/skuId — skipping count", env.eventId(), type);
        } else {
            countPick(p, env.occurredAt());
        }

        processedEvents.save(new VelocityProcessedEvent(env.eventId(), type, env.streamId(), env.seq()));
        advanceOffset(env);
        log.debug("Counted velocity {} event {} (stream {} seq {})",
                type, env.eventId(), env.streamId(), env.seq());
    }

    private void countPick(VelocityMovementPayload p, Instant occurredAt) {
        SkuVelocity row = velocity.findByWarehouseIdAndSkuId(p.warehouseId(), p.skuId())
                .orElseGet(() -> {
                    SkuVelocity created = new SkuVelocity();
                    created.setWarehouseId(p.warehouseId());
                    created.setSkuId(p.skuId());
                    return created;
                });
        row.setPendingPicks(row.getPendingPicks().add(BigDecimal.ONE));
        Instant when = occurredAt != null ? occurredAt : Instant.now();
        if (row.getLastPickAt() == null || when.isAfter(row.getLastPickAt())) {
            row.setLastPickAt(when);
        }
        velocity.save(row);
    }

    private void advanceOffset(EventEnvelope env) {
        VelocityOffset offset = offsets.findById(VELOCITY_PROJECTION).orElseGet(() -> {
            VelocityOffset created = new VelocityOffset();
            created.setProjection(VELOCITY_PROJECTION);
            return created;
        });
        offset.setLastEventId(env.eventId());
        offset.setLastSeq(env.seq());
        offsets.save(offset);
    }
}
