package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.domain.Batch;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.BatchRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the inventory context against a real PostgreSQL 16: runs Flyway then
 * Hibernate {@code ddl-auto=validate}, proving the inventory entity mappings match
 * the migrated schema. Also exercises the null-safe stock-bucket lookup and the
 * available-quantity aggregate.
 */
@SpringBootTest
@Testcontainers
class InventoryPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // No Kafka broker in this test — don't start the projection listener.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    BatchRepository batches;

    @Autowired
    StockRepository stock;

    @Test
    void persistsBatchAndStockBucket() {
        UUID warehouseId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        Batch batch = new Batch();
        batch.setWarehouseId(warehouseId);
        batch.setSkuId(skuId);
        batch.setBatchNumber("LOT-42");
        batch = batches.save(batch);

        Stock row = new Stock();
        row.setWarehouseId(warehouseId);
        row.setSkuId(skuId);
        row.setBatchId(batch.getId());
        row.setLocationId(locationId);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal("10.0000"));
        stock.save(row);

        // Null-safe bucket resolution (hu_id is null here).
        assertThat(stock.findBucket(warehouseId, skuId, batch.getId(), locationId, null, "AVAILABLE"))
                .isPresent();
        assertThat(stock.sumAvailable(warehouseId, skuId)).isEqualByComparingTo("10.0000");
    }

    @Test
    void treatsNullBatchBucketAsDistinctFromBatchedBucket() {
        UUID warehouseId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        Stock noBatch = new Stock();
        noBatch.setWarehouseId(warehouseId);
        noBatch.setSkuId(skuId);
        noBatch.setLocationId(locationId);
        noBatch.setQty(new BigDecimal("5.0000"));
        stock.save(noBatch);

        assertThat(stock.findBucket(warehouseId, skuId, null, locationId, null, "AVAILABLE"))
                .isPresent()
                .get()
                .satisfies(s -> assertThat(s.getQty()).isEqualByComparingTo("5.0000"));
    }
}
