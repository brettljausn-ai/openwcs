package org.openwcs.orders.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.openwcs.orders.api.CreateOrderRequest;
import org.openwcs.orders.api.DemoSeedResult;
import org.openwcs.orders.client.MasterDataClient;
import org.openwcs.orders.domain.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Demo "add orders" seeding (build.md §4.8): bulk-creates a handful of sample INBOUND or OUTBOUND
 * orders from the seeded demo catalog so the inbound / outbound screens can be demoed with one
 * click. Each line references a demo SKU (ownerClient=DEMO); requires demo mode to be on (i.e. the
 * demo catalog to exist) and is a no-op-with-error otherwise.
 */
@Service
public class DemoSeedService {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedService.class);

    private final OrderService orders;
    private final MasterDataClient masterData;
    private final Random rnd = new Random();

    public DemoSeedService(OrderService orders, MasterDataClient masterData) {
        this.orders = orders;
        this.masterData = masterData;
    }

    /**
     * Create {@code count} demo orders of the given direction for a warehouse.
     *
     * @throws IllegalStateException if the demo catalog is empty (demo mode not enabled)
     */
    public DemoSeedResult seed(UUID warehouseId, String type, int count) {
        OrderType orderType = OrderType.valueOf(type == null ? "OUTBOUND" : type.toUpperCase());
        if (orderType != OrderType.INBOUND && orderType != OrderType.OUTBOUND) {
            throw new IllegalArgumentException("type must be INBOUND or OUTBOUND");
        }
        int n = count <= 0 ? 10 : Math.min(count, 100);

        List<UUID> demoSkus = masterData.listDemoSkus();
        if (demoSkus.isEmpty()) {
            throw new IllegalStateException("Demo mode is not enabled (no demo SKUs to build orders from).");
        }

        int created = 0;
        for (int i = 0; i < n; i++) {
            orders.create(buildOrder(warehouseId, orderType, demoSkus));
            created++;
        }
        log.info("demo seed for warehouse {}: created {} {} orders from a catalog of {} demo SKUs",
                warehouseId, created, orderType, demoSkus.size());
        return new DemoSeedResult(created);
    }

    private CreateOrderRequest buildOrder(UUID warehouseId, OrderType type, List<UUID> demoSkus) {
        String ref = "DEMO-" + type + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        boolean outbound = type == OrderType.OUTBOUND;

        // Outbound demo orders are deliberately small (a single line, low quantity) so cubing fits
        // them into one shipper. Inbound (expected receipts) can be larger, multi-line.
        int lineCount = outbound ? 1 : 1 + rnd.nextInt(5);
        Set<UUID> chosen = new LinkedHashSet<>();
        while (chosen.size() < lineCount && chosen.size() < demoSkus.size()) {
            chosen.add(demoSkus.get(rnd.nextInt(demoSkus.size())));
        }
        List<CreateOrderRequest.Line> lines = new ArrayList<>();
        for (UUID skuId : chosen) {
            long qty = outbound ? 1L + rnd.nextInt(3) : 10L + rnd.nextInt(91);
            lines.add(new CreateOrderRequest.Line(skuId, BigDecimal.valueOf(qty)));
        }

        Integer priority = outbound ? 1 + rnd.nextInt(10) : 5;
        Instant dispatchBy = outbound ? Instant.now().plus(1 + rnd.nextInt(7), ChronoUnit.DAYS) : null;
        return new CreateOrderRequest(ref, warehouseId, type.name(), "DEMO", priority, dispatchBy,
                null, null, null, null, lines);
    }
}
