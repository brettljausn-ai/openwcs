package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.common.EventEnvelope;
import org.openwcs.inventory.projection.InventoryEventTypes;
import org.openwcs.inventory.projection.StockProjectionService;
import org.openwcs.inventory.repo.ProcessedEventRepository;
import org.openwcs.inventory.repo.ProjectionOffsetRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the transaction-log → stock projection against a real PostgreSQL 16,
 * applying envelopes through the same Map→record decode path the Kafka consumer uses.
 * Verifies stock.qty movement, idempotency on event_id, and projection_offset advance.
 */
@SpringBootTest
@Testcontainers
class StockProjectionServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // No broker in this test — the projection is driven directly.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    StockProjectionService projection;

    @Autowired
    StockRepository stock;

    @Autowired
    ProjectionOffsetRepository offsets;

    @Autowired
    ProcessedEventRepository processedEvents;

    @Autowired
    org.openwcs.inventory.repo.HandlingUnitRepository handlingUnits;

    private long seq = 1;

    private EventEnvelope envelope(String type, Map<String, Object> payload) {
        return new EventEnvelope(
                UUID.randomUUID(), "stream-1", seq++, type,
                Instant.parse("2026-06-02T10:00:00Z"), Instant.parse("2026-06-02T10:00:01Z"),
                "test", null, 1, payload);
    }

    @Test
    void goodsReceivedCreatesBucketAndAdvancesOffset() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID loc = UUID.randomUUID();

        EventEnvelope env = envelope(InventoryEventTypes.GOODS_RECEIVED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", loc,
                "qty", new BigDecimal("12"), "uomCode", "EACH"));
        projection.apply(env);

        assertThat(stock.sumAvailable(wh, sku)).isEqualByComparingTo("12");
        assertThat(offsets.findById("stock"))
                .get()
                .satisfies(o -> assertThat(o.getLastEventId()).isEqualTo(env.eventId()));
    }

    @Test
    void pickDecrementsAvailableQty() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID loc = UUID.randomUUID();

        projection.apply(envelope(InventoryEventTypes.GOODS_RECEIVED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", loc, "qty", new BigDecimal("10"))));
        projection.apply(envelope(InventoryEventTypes.PICKED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", loc, "qty", new BigDecimal("4"))));

        assertThat(stock.sumAvailable(wh, sku)).isEqualByComparingTo("6");
    }

    @Test
    void moveShiftsQtyBetweenLocations() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        projection.apply(envelope(InventoryEventTypes.GOODS_RECEIVED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", from, "qty", new BigDecimal("8"))));
        projection.apply(envelope(InventoryEventTypes.STOCK_MOVED, Map.of(
                "warehouseId", wh, "skuId", sku, "qty", new BigDecimal("5"),
                "fromLocationId", from, "toLocationId", to)));

        // Total unchanged; distribution moved.
        assertThat(stock.sumAvailable(wh, sku)).isEqualByComparingTo("8");
        assertThat(stock.findBucket(wh, sku, null, from, null, "AVAILABLE"))
                .get().satisfies(s -> assertThat(s.getQty()).isEqualByComparingTo("3"));
        assertThat(stock.findBucket(wh, sku, null, to, null, "AVAILABLE"))
                .get().satisfies(s -> assertThat(s.getQty()).isEqualByComparingTo("5"));
    }

    @Test
    void statusChangeMovesQtyOutOfAvailable() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID loc = UUID.randomUUID();

        projection.apply(envelope(InventoryEventTypes.GOODS_RECEIVED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", loc, "qty", new BigDecimal("10"))));
        projection.apply(envelope(InventoryEventTypes.STOCK_STATUS_CHANGED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", loc, "qty", new BigDecimal("3"),
                "fromStatus", "AVAILABLE", "toStatus", "LOCKED")));

        assertThat(stock.sumAvailable(wh, sku)).isEqualByComparingTo("7");
        assertThat(stock.findBucket(wh, sku, null, loc, null, "LOCKED"))
                .get().satisfies(s -> assertThat(s.getQty()).isEqualByComparingTo("3"));
    }

    @Test
    void reapplyingSameEventIsIdempotent() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID loc = UUID.randomUUID();

        EventEnvelope env = envelope(InventoryEventTypes.GOODS_RECEIVED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", loc, "qty", new BigDecimal("9")));

        projection.apply(env);
        projection.apply(env); // redelivery / replay of the same event_id

        assertThat(stock.sumAvailable(wh, sku)).isEqualByComparingTo("9");
        assertThat(processedEvents.existsById(env.eventId())).isTrue();
    }

    @Test
    void huBoundAdjustmentBooksToTheHusCurrentLocationNotTheEventsStaleOne() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID staleSlot = UUID.randomUUID(); // where the count line captured the stock
        UUID station = UUID.randomUUID(); // where the tote ACTUALLY is when the count confirms

        org.openwcs.inventory.domain.HandlingUnit tote = new org.openwcs.inventory.domain.HandlingUnit();
        tote.setWarehouseId(wh);
        tote.setCode("HU-PROJ-1");
        tote.setLocationId(station);
        tote = handlingUnits.save(tote);

        projection.apply(envelope(InventoryEventTypes.STOCK_ADJUSTED, Map.of(
                "warehouseId", wh, "skuId", sku, "locationId", staleSlot, "huId", tote.getHuId(),
                "qtyDelta", new BigDecimal("15"))));

        // Stock rides in the HU: the bucket lands where the tote IS, never at the stale slot.
        assertThat(stock.findBucket(wh, sku, null, station, tote.getHuId(), "AVAILABLE"))
                .get().satisfies(s -> assertThat(s.getQty()).isEqualByComparingTo("15"));
        assertThat(stock.findBucket(wh, sku, null, staleSlot, tote.getHuId(), "AVAILABLE")).isEmpty();
    }

    @Test
    void nonStockEventAdvancesOffsetWithoutTouchingStock() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();

        Map<String, Object> payload = new HashMap<>();
        payload.put("note", "not a movement");
        EventEnvelope env = envelope("SkuCreated", payload);
        projection.apply(env);

        assertThat(stock.sumAvailable(wh, sku)).isEqualByComparingTo("0");
        assertThat(processedEvents.existsById(env.eventId())).isFalse();
        assertThat(offsets.findById("stock"))
                .get().satisfies(o -> assertThat(o.getLastEventId()).isEqualTo(env.eventId()));
    }
}
