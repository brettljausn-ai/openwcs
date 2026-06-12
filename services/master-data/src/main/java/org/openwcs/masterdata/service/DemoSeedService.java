package org.openwcs.masterdata.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.openwcs.masterdata.api.DemoResult;
import org.openwcs.masterdata.api.DemoStatusView;
import org.openwcs.masterdata.domain.Barcode;
import org.openwcs.masterdata.domain.BarcodeType;
import org.openwcs.masterdata.domain.HandlingUnitType;
import org.openwcs.masterdata.domain.Shipper;
import org.openwcs.masterdata.domain.Sku;
import org.openwcs.masterdata.domain.SystemConfiguration;
import org.openwcs.masterdata.domain.UnitOfMeasure;
import org.openwcs.masterdata.repo.BarcodeRepository;
import org.openwcs.masterdata.repo.BarcodeTypeRepository;
import org.openwcs.masterdata.repo.HandlingUnitTypeRepository;
import org.openwcs.masterdata.repo.LocationRepository;
import org.openwcs.masterdata.repo.ShipperRepository;
import org.openwcs.masterdata.repo.SkuRepository;
import org.openwcs.masterdata.repo.SystemConfigurationRepository;
import org.openwcs.masterdata.repo.UnitOfMeasureRepository;
import org.openwcs.masterdata.repo.WarehouseFulfillmentConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode (build.md). Switching demo mode ON seeds a sample catalog directly via the repositories
 * — bypassing the host-managed guard on the SKU controller, since this is an internal operation —
 * but ONLY when the system is empty (no SKUs ⇒ no host data). Switching it OFF is a full catalog
 * reset: the whole SKU catalog (with UoMs and barcodes) plus the demo shippers and demo HU type are
 * removed, while warehouses, locations, storage blocks, topology and GTP / other configuration are
 * kept. Since demo mode only enables on an empty catalog, removing every SKU restores exactly the
 * pre-demo state.
 */
@Service
public class DemoSeedService {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedService.class);

    static final String DEMO_FLAG = "DEMO_MODE_ENABLED";
    static final String SKU_PREFIX = "DEMO-SKU-";
    static final String SHIPPER_PREFIX = "DEMO-";
    static final String STORAGE_HU_NAME = "DEMO-STORAGE-HU";
    private static final int SKU_COUNT = 100;

    // Fun movie-merch SKU names (franchise item × merch type → 100 unique descriptions).
    private static final String[] FRANCHISE_ITEMS = {
        "Star Wars Millennium Falcon", "Star Wars X-Wing", "Star Wars Darth Vader", "Star Wars Grogu",
        "Star Wars Lightsaber", "Star Wars Stormtrooper", "Star Wars BB-8", "Star Wars Death Star",
        "Harry Potter Hedwig", "Harry Potter Golden Snitch", "Harry Potter Hogwarts Castle", "Harry Potter Wand",
        "Harry Potter Sorting Hat", "Harry Potter Marauder's Map", "Harry Potter Nimbus 2000", "Harry Potter House Scarf",
        "Marvel Iron Man Helmet", "Marvel Captain America Shield", "Marvel Thor's Hammer", "Marvel Spider-Man Mask",
        "Marvel Infinity Gauntlet", "Marvel Hulk Fists", "Marvel Black Panther Mask", "Marvel Groot",
        "Lord of the Rings One Ring", "Jurassic Park T-Rex", "Back to the Future DeLorean",
    };
    private static final String[] MERCH_TYPES = {
        "model kit", "plush", "action figure", "building-block set", "mug", "poster", "keychain", "tee", "replica", "figurine",
    };

    private final SkuRepository skus;
    private final UnitOfMeasureRepository uoms;
    private final BarcodeRepository barcodes;
    private final BarcodeTypeRepository barcodeTypes;
    private final HandlingUnitTypeRepository huTypes;
    private final ShipperRepository shippers;
    private final LocationRepository locations;
    private final SystemConfigurationRepository config;
    private final WarehouseFulfillmentConfigRepository fulfillmentConfigs;

    public DemoSeedService(SkuRepository skus, UnitOfMeasureRepository uoms, BarcodeRepository barcodes,
            BarcodeTypeRepository barcodeTypes, HandlingUnitTypeRepository huTypes, ShipperRepository shippers,
            LocationRepository locations, SystemConfigurationRepository config,
            WarehouseFulfillmentConfigRepository fulfillmentConfigs) {
        this.skus = skus;
        this.uoms = uoms;
        this.barcodes = barcodes;
        this.barcodeTypes = barcodeTypes;
        this.huTypes = huTypes;
        this.shippers = shippers;
        this.locations = locations;
        this.config = config;
        this.fulfillmentConfigs = fulfillmentConfigs;
    }

    /** A fun, unique demo product name for SKU index i (1-based). */
    static String demoItemName(int i) {
        int idx = (i - 1) % FRANCHISE_ITEMS.length;
        int type = ((i - 1) / FRANCHISE_ITEMS.length) % MERCH_TYPES.length;
        return FRANCHISE_ITEMS[idx] + " " + MERCH_TYPES[type];
    }

    @Transactional(readOnly = true)
    public DemoStatusView status(UUID warehouseId) {
        long skuCount = skus.count();
        boolean enabled = config.findById(DEMO_FLAG)
                .map(c -> Boolean.parseBoolean(c.getValue())).orElse(false);
        boolean hasLocations = warehouseId != null && locations.countByWarehouseId(warehouseId) > 0;
        return new DemoStatusView(enabled, skuCount == 0 && hasLocations, skuCount);
    }

    /** Seed the demo catalog. Allowed only on an empty (host-free) system that already has locations. */
    @Transactional
    public DemoResult enable(UUID warehouseId) {
        if (skus.count() > 0) {
            log.warn("demo enable rejected: the sku catalog already holds {} skus (host data present);"
                    + " demo mode only seeds an empty system, nothing was changed", skus.count());
            throw new IllegalStateException(
                    "Demo mode can only be enabled on a fresh system with no host data (the SKU catalog is not empty).");
        }
        if (warehouseId == null || locations.countByWarehouseId(warehouseId) == 0) {
            log.warn("demo enable rejected: warehouse {} has no storage locations;"
                    + " demo mode places stock into existing locations, nothing was changed", warehouseId);
            throw new IllegalStateException(
                    "Create storage locations for this warehouse first — demo mode places stock into existing locations.");
        }
        BarcodeType ean13 = barcodeTypes.findByName("EAN13")
                .orElseThrow(() -> new IllegalStateException("EAN13 barcode type is missing from reference data."));

        Random rnd = new Random(42); // reproducible demo data
        List<String> demoImages = loadDemoImages();
        long seq = 1;
        int skuN = 0;
        int uomN = 0;
        int bcN = 0;

        for (int i = 1; i <= SKU_COUNT; i++) {
            Sku sku = new Sku();
            sku.setCode(String.format("%s%03d", SKU_PREFIX, i));
            sku.setDescription(demoItemName(i));
            sku.setOwnerClient("DEMO");
            sku.setStatus("ACTIVE");
            if (!demoImages.isEmpty()) {
                sku.setImageUrl(demoImages.get(rnd.nextInt(demoImages.size())));
            }
            sku = skus.save(sku);
            skuN++;

            // Item size between the smallest (10×20×10 mm / 100 g) and the largest (100×300×100 mm / 2 kg).
            BigDecimal lengthMm = bd(10 + rnd.nextInt(91));   // 10..100
            BigDecimal widthMm = bd(20 + rnd.nextInt(281));   // 20..300
            BigDecimal heightMm = bd(10 + rnd.nextInt(91));   // 10..100
            BigDecimal weightG = bd(100 + rnd.nextInt(1901)); // 100..2000

            UnitOfMeasure base = new UnitOfMeasure();
            base.setSkuId(sku.getId());
            base.setCode("EA");
            base.setBaseUnit(true);
            base.setLengthMm(lengthMm);
            base.setWidthMm(widthMm);
            base.setHeightMm(heightMm);
            base.setWeightG(weightG);
            base = uoms.save(base);
            uomN++;

            int multiple = rnd.nextBoolean() ? 5 : 10;
            UnitOfMeasure pack = new UnitOfMeasure();
            pack.setSkuId(sku.getId());
            pack.setCode("PACK");
            pack.setBaseUnit(false);
            pack.setParentUomId(base.getId());
            pack.setQtyInParent(bd(multiple));
            pack.setLengthMm(lengthMm);
            pack.setWidthMm(widthMm);
            pack.setHeightMm(heightMm.multiply(bd(multiple))); // packs stack the base height
            pack.setWeightG(weightG.multiply(bd(multiple)));
            pack = uoms.save(pack);
            uomN++;

            // EAN13 barcode per unit of measure.
            barcodes.save(barcode(sku.getId(), base.getId(), ean13.getId(), ean13(seq++)));
            barcodes.save(barcode(sku.getId(), pack.getId(), ean13.getId(), ean13(seq++)));
            bcN += 2;
        }

        int shipperN = 0;
        if (warehouseId != null) {
            shipperN += seedShipper(warehouseId, "DEMO-CARTON-L", "Demo carton (large)", "CARTON", 600, 400, 400, 300);
            shipperN += seedShipper(warehouseId, "DEMO-CARTON-S", "Demo carton (small)", "CARTON", 600, 400, 200, 180);
            shipperN += seedShipper(warehouseId, "DEMO-BAG", "Demo bag", "BAG", 300, 400, 400, 40);
        }

        int huN = 0;
        if (huTypes.findByName(STORAGE_HU_NAME).isEmpty()) {
            HandlingUnitType hu = new HandlingUnitType();
            hu.setName(STORAGE_HU_NAME);
            hu.setLengthMm(bd(600));
            hu.setWidthMm(bd(400));
            hu.setHeightMm(bd(400));
            hu.setWeightLimitG(bd(20_000));
            hu.setCompartments(1);
            hu.setStorableInAutomation(true);
            hu.setTransportableOnConveyor(true);
            huTypes.save(hu);
            huN = 1;
        }

        setFlag(true);
        log.info("demo mode enabled: seeded {} skus (codes prefixed {}), {} uoms, {} barcodes,"
                        + " {} demo shippers and {} demo hu type(s) for warehouse {}"
                        + " because an admin switched demo mode on for an empty catalog",
                skuN, SKU_PREFIX, uomN, bcN, shipperN, huN, warehouseId);
        return new DemoResult(skuN, uomN, bcN, shipperN, huN);
    }

    /**
     * Full catalog reset. Demo mode only ever enables on an empty (host-free) system, so on
     * disable the WHOLE SKU catalog goes — every SKU with its units of measure, barcodes and
     * profiles — not just the DEMO-prefixed rows, restoring the pre-demo empty catalog. Demo
     * shippers and the demo HU type are removed too; warehouses, locations, storage blocks,
     * topology and other configuration are kept.
     */
    @Transactional
    public DemoResult disable() {
        // Bulk statements only — an emulator run can grow the data far beyond what should ever
        // be loaded into memory. Counts are taken first, then each table is cleared with a single
        // DELETE. Order is FK-safe: barcode (references uom + sku) → uom → sku. Deleting all UoM
        // rows in ONE statement also satisfies the parent_uom_id self-FK (constraints are checked
        // per statement), which the old row-by-row delete had to order around. sku_profile and
        // dangerous_goods cascade at the DB level (ON DELETE CASCADE on sku).
        int bcN = (int) barcodes.count();
        int uomN = (int) uoms.count();
        int skuN = (int) skus.count();
        barcodes.deleteAllInBatch();
        uoms.deleteAllInBatch();
        skus.deleteAllInBatch();

        // The cubing config's default_shipper_id carries a FK to shipper. When an admin picked a
        // demo carton as the default, deleting the shipper would violate that FK (the cause of the
        // 409 "request conflicts with existing data" on disable) — unset the reference first.
        fulfillmentConfigs.clearDefaultShipperByCodePrefix(SHIPPER_PREFIX);
        int shipperN = shippers.deleteByCodePrefix(SHIPPER_PREFIX);

        int huN = 0;
        var hu = huTypes.findByName(STORAGE_HU_NAME);
        if (hu.isPresent()) {
            huTypes.delete(hu.get());
            huN = 1;
        }

        setFlag(false);
        log.info("demo mode disabled: full catalog reset removed {} skus, {} uoms, {} barcodes,"
                        + " {} demo shippers and {} demo hu type(s)"
                        + " because disable restores the pre-demo empty catalog",
                skuN, uomN, bcN, shipperN, huN);
        return new DemoResult(skuN, uomN, bcN, shipperN, huN);
    }

    private int seedShipper(UUID warehouseId, String code, String name, String type,
            int lengthMm, int widthMm, int heightMm, int tareG) {
        if (shippers.findByWarehouseIdAndCode(warehouseId, code).isPresent()) {
            return 0;
        }
        Shipper s = new Shipper();
        s.setWarehouseId(warehouseId);
        s.setCode(code);
        s.setName(name);
        s.setShipperType(type);
        s.setLengthMm(bd(lengthMm));
        s.setWidthMm(bd(widthMm));
        s.setHeightMm(bd(heightMm));
        s.setTareWeightG(bd(tareG));
        s.setStatus("ACTIVE");
        shippers.save(s);
        return 1;
    }

    /** Demo product image URLs bundled as a classpath resource; empty if absent. */
    private List<String> loadDemoImages() {
        List<String> urls = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/demo-sku-images.txt")) {
            if (in == null) {
                return urls;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String url = line.trim();
                    if (url.startsWith("http")) {
                        urls.add(url);
                    }
                }
            }
        } catch (IOException e) {
            // Demo images are best-effort; fall back to none.
            log.warn("demo sku images skipped: could not read /demo-sku-images.txt ({});"
                    + " demo skus are seeded without product images", e.toString());
        }
        return urls;
    }

    private void setFlag(boolean on) {
        SystemConfiguration c = config.findById(DEMO_FLAG)
                .orElseGet(() -> new SystemConfiguration(DEMO_FLAG, "false"));
        c.setValue(Boolean.toString(on));
        config.save(c);
    }

    private static Barcode barcode(UUID skuId, UUID uomId, UUID typeId, String value) {
        Barcode b = new Barcode();
        b.setSkuId(skuId);
        b.setUomId(uomId);
        b.setBarcodeTypeId(typeId);
        b.setValue(value);
        return b;
    }

    private static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    /** Build a valid 13-digit EAN-13 (12-digit body from the sequence + standard check digit). */
    static String ean13(long seq) {
        String body = String.format("%012d", seq);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int d = body.charAt(i) - '0';
            sum += (i % 2 == 0) ? d : d * 3;
        }
        int check = (10 - (sum % 10)) % 10;
        return body + check;
    }
}
