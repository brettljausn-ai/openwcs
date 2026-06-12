package org.openwcs.inventory.projection;

import static org.openwcs.inventory.projection.InventoryEventTypes.GOODS_RECEIVED;
import static org.openwcs.inventory.projection.InventoryEventTypes.PICKED;
import static org.openwcs.inventory.projection.InventoryEventTypes.PUTAWAY_COMPLETED;
import static org.openwcs.inventory.projection.InventoryEventTypes.STOCK_ADJUSTED;
import static org.openwcs.inventory.projection.InventoryEventTypes.STOCK_MOVED;
import static org.openwcs.inventory.projection.InventoryEventTypes.STOCK_STATUS_CHANGED;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.openwcs.common.EventEnvelope;
import org.openwcs.inventory.domain.ProcessedEvent;
import org.openwcs.inventory.domain.ProjectionOffset;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.projection.StockMovementPayloads.Adjust;
import org.openwcs.inventory.projection.StockMovementPayloads.BucketQty;
import org.openwcs.inventory.projection.StockMovementPayloads.Move;
import org.openwcs.inventory.projection.StockMovementPayloads.StatusChange;
import org.openwcs.inventory.repo.ProcessedEventRepository;
import org.openwcs.inventory.repo.ProjectionOffsetRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects the streamed transaction log onto the durable {@code stock} table
 * (build.md §4.2, §5.4). Each event is applied exactly once — the {@code processed_event}
 * inbox guards against redelivery/replay — and the {@code stock} projection cursor is
 * advanced so progress is observable and a rebuild can resume.
 *
 * <p>The whole apply is one transaction: stock mutation, inbox insert, and cursor
 * advance commit together (or not at all), keeping the read model consistent with the log.
 */
@Service
public class StockProjectionService {

    private static final Logger log = LoggerFactory.getLogger(StockProjectionService.class);

    /** Cursor key in {@code projection_offset}. */
    static final String STOCK_PROJECTION = "stock";

    private static final String DEFAULT_STATUS = "AVAILABLE";
    private static final String DEFAULT_UOM = "EACH";

    private static final Set<String> STOCK_EVENTS = Set.of(
            GOODS_RECEIVED, PUTAWAY_COMPLETED, STOCK_MOVED, PICKED, STOCK_ADJUSTED, STOCK_STATUS_CHANGED);

    private final StockRepository stock;
    private final ProcessedEventRepository processedEvents;
    private final ProjectionOffsetRepository offsets;
    private final ObjectMapper objectMapper;

    public StockProjectionService(StockRepository stock,
                                  ProcessedEventRepository processedEvents,
                                  ProjectionOffsetRepository offsets,
                                  ObjectMapper objectMapper) {
        this.stock = stock;
        this.processedEvents = processedEvents;
        this.offsets = offsets;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(EventEnvelope env) {
        String type = env.eventType();
        if (!STOCK_EVENTS.contains(type)) {
            // Not a stock-affecting event — still track consumption progress.
            advanceOffset(env);
            return;
        }
        if (processedEvents.existsById(env.eventId())) {
            log.debug("Skipping already-applied event {} ({})", env.eventId(), type);
            return;
        }

        dispatch(env, type);

        processedEvents.save(new ProcessedEvent(env.eventId(), type, env.streamId(), env.seq()));
        advanceOffset(env);
        log.debug("Applied {} event {} (stream {} seq {})", type, env.eventId(), env.streamId(), env.seq());
    }

    private void dispatch(EventEnvelope env, String type) {
        UUID eventId = env.eventId();
        switch (type) {
            case GOODS_RECEIVED -> {
                BucketQty p = payload(env, BucketQty.class);
                addToBucket(p.warehouseId(), p.skuId(), p.batchId(), p.locationId(), p.huId(),
                        p.status(), required(p.qty(), "qty"), p.uomCode(), eventId, type);
            }
            case PICKED -> {
                BucketQty p = payload(env, BucketQty.class);
                addToBucket(p.warehouseId(), p.skuId(), p.batchId(), p.locationId(), p.huId(),
                        p.status(), required(p.qty(), "qty").negate(), p.uomCode(), eventId, type);
            }
            case PUTAWAY_COMPLETED, STOCK_MOVED -> {
                Move p = payload(env, Move.class);
                BigDecimal qty = required(p.qty(), "qty");
                addToBucket(p.warehouseId(), p.skuId(), p.batchId(), p.fromLocationId(), p.fromHuId(),
                        p.status(), qty.negate(), p.uomCode(), eventId, type);
                addToBucket(p.warehouseId(), p.skuId(), p.batchId(), p.toLocationId(), p.toHuId(),
                        p.status(), qty, p.uomCode(), eventId, type);
            }
            case STOCK_ADJUSTED -> {
                Adjust p = payload(env, Adjust.class);
                addToBucket(p.warehouseId(), p.skuId(), p.batchId(), p.locationId(), p.huId(),
                        p.status(), required(p.qtyDelta(), "qtyDelta"), p.uomCode(), eventId, type);
            }
            case STOCK_STATUS_CHANGED -> {
                StatusChange p = payload(env, StatusChange.class);
                BigDecimal qty = required(p.qty(), "qty");
                addToBucket(p.warehouseId(), p.skuId(), p.batchId(), p.locationId(), p.huId(),
                        p.fromStatus(), qty.negate(), p.uomCode(), eventId, type);
                addToBucket(p.warehouseId(), p.skuId(), p.batchId(), p.locationId(), p.huId(),
                        p.toStatus(), qty, p.uomCode(), eventId, type);
            }
            default -> throw new IllegalStateException("Unhandled stock event type: " + type);
        }
    }

    /**
     * Apply a signed quantity delta to one physical stock bucket, creating the row on
     * first increment. Throws rather than corrupt state if a decrement would underflow.
     */
    private void addToBucket(UUID warehouseId, UUID skuId, UUID batchId, UUID locationId, UUID huId,
                             String status, BigDecimal delta, String uomCode, UUID eventId, String eventType) {
        String resolvedStatus = statusOrDefault(status);
        Stock row = stock.findBucket(warehouseId, skuId, batchId, locationId, huId, resolvedStatus)
                .orElse(null);

        boolean created = false;
        if (row == null) {
            if (delta.signum() < 0) {
                log.info("stock integrity error: {} event {} decrements a non-existent bucket"
                                + " (sku {} location {} hu {} status {}); throwing so the projection"
                                + " halts instead of corrupting the read model",
                        eventType, eventId, skuId, locationId, huId, resolvedStatus);
                throw new IllegalStateException(
                        "Cannot decrement a non-existent stock bucket (sku=%s location=%s status=%s)"
                                .formatted(skuId, locationId, resolvedStatus));
            }
            row = new Stock();
            row.setWarehouseId(warehouseId);
            row.setSkuId(skuId);
            row.setBatchId(batchId);
            row.setLocationId(locationId);
            row.setHuId(huId);
            row.setStatus(resolvedStatus);
            row.setQty(BigDecimal.ZERO);
            row.setUomCode(uomOrDefault(uomCode));
            created = true;
        }

        BigDecimal previousQty = row.getQty();
        BigDecimal newQty = previousQty.add(delta);
        if (newQty.signum() < 0) {
            log.info("stock integrity error: {} event {} would drive bucket"
                            + " (sku {} location {} hu {} status {}) negative (qty {} delta {});"
                            + " throwing so the projection halts instead of corrupting the read model",
                    eventType, eventId, skuId, locationId, huId, resolvedStatus, previousQty, delta);
            throw new IllegalStateException(
                    "Insufficient stock: bucket (sku=%s location=%s status=%s) would go negative"
                            .formatted(skuId, locationId, resolvedStatus));
        }
        row.setQty(newQty);
        row.setLastEventId(eventId);
        stock.save(row);

        if (created) {
            log.info("stock bucket created: sku {} at location {} in hu {} status {} qty {} {}"
                            + " because first increment from event {} ({})",
                    skuId, locationId, huId, resolvedStatus, newQty, row.getUomCode(), eventType, eventId);
        } else if (newQty.signum() == 0) {
            log.info("stock bucket exhausted: sku {} at location {} in hu {} status {} reached qty 0"
                            + " after delta {} {} from event {} ({})",
                    skuId, locationId, huId, resolvedStatus, delta, row.getUomCode(), eventType, eventId);
        } else {
            log.debug("stock bucket updated: sku {} at location {} in hu {} status {} qty {} -> {} {}"
                            + " from event {} ({})",
                    skuId, locationId, huId, resolvedStatus, previousQty, newQty, row.getUomCode(),
                    eventType, eventId);
        }
    }

    private void advanceOffset(EventEnvelope env) {
        ProjectionOffset offset = offsets.findById(STOCK_PROJECTION).orElseGet(() -> {
            ProjectionOffset created = new ProjectionOffset();
            created.setProjection(STOCK_PROJECTION);
            return created;
        });
        offset.setLastEventId(env.eventId());
        offset.setLastSeq(env.seq());
        offsets.save(offset);
    }

    private <T> T payload(EventEnvelope env, Class<T> type) {
        return objectMapper.convertValue(env.payload(), type);
    }

    private static BigDecimal required(BigDecimal value, String field) {
        return Objects.requireNonNull(value, () -> field + " is required");
    }

    private static String statusOrDefault(String status) {
        return (status == null || status.isBlank()) ? DEFAULT_STATUS : status;
    }

    private static String uomOrDefault(String uom) {
        return (uom == null || uom.isBlank()) ? DEFAULT_UOM : uom;
    }
}
