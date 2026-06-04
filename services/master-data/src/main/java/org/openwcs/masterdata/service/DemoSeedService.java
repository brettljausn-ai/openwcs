package org.openwcs.masterdata.service;

import java.math.BigDecimal;
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
import org.openwcs.masterdata.repo.ShipperRepository;
import org.openwcs.masterdata.repo.SkuRepository;
import org.openwcs.masterdata.repo.SystemConfigurationRepository;
import org.openwcs.masterdata.repo.UnitOfMeasureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode (build.md). Switching demo mode ON seeds a sample catalog directly via the repositories
 * — bypassing the host-managed guard on the SKU controller, since this is an internal operation —
 * but ONLY when the system is empty (no SKUs ⇒ no host data). Switching it OFF removes everything
 * demo mode created (all {@code DEMO-} prefixed master data) and leaves topology / GTP / other
 * configuration untouched. Every demo record is tagged with a {@code DEMO-} code/name so cleanup is
 * exact.
 */
@Service
public class DemoSeedService {

    static final String DEMO_FLAG = "DEMO_MODE_ENABLED";
    static final String SKU_PREFIX = "DEMO-SKU-";
    static final String SHIPPER_PREFIX = "DEMO-";
    static final String STORAGE_HU_NAME = "DEMO-STORAGE-HU";
    private static final int SKU_COUNT = 100;

    private final SkuRepository skus;
    private final UnitOfMeasureRepository uoms;
    private final BarcodeRepository barcodes;
    private final BarcodeTypeRepository barcodeTypes;
    private final HandlingUnitTypeRepository huTypes;
    private final ShipperRepository shippers;
    private final SystemConfigurationRepository config;

    public DemoSeedService(SkuRepository skus, UnitOfMeasureRepository uoms, BarcodeRepository barcodes,
            BarcodeTypeRepository barcodeTypes, HandlingUnitTypeRepository huTypes, ShipperRepository shippers,
            SystemConfigurationRepository config) {
        this.skus = skus;
        this.uoms = uoms;
        this.barcodes = barcodes;
        this.barcodeTypes = barcodeTypes;
        this.huTypes = huTypes;
        this.shippers = shippers;
        this.config = config;
    }

    @Transactional(readOnly = true)
    public DemoStatusView status() {
        long skuCount = skus.count();
        boolean enabled = config.findById(DEMO_FLAG)
                .map(c -> Boolean.parseBoolean(c.getValue())).orElse(false);
        return new DemoStatusView(enabled, skuCount == 0, skuCount);
    }

    /** Seed the demo catalog. Allowed only on an empty (host-free) system. */
    @Transactional
    public DemoResult enable(UUID warehouseId) {
        if (skus.count() > 0) {
            throw new IllegalStateException(
                    "Demo mode can only be enabled on a fresh system with no host data (the SKU catalog is not empty).");
        }
        BarcodeType ean13 = barcodeTypes.findByName("EAN13")
                .orElseThrow(() -> new IllegalStateException("EAN13 barcode type is missing from reference data."));

        Random rnd = new Random(42); // reproducible demo data
        long seq = 1;
        int skuN = 0;
        int uomN = 0;
        int bcN = 0;

        for (int i = 1; i <= SKU_COUNT; i++) {
            Sku sku = new Sku();
            sku.setCode(String.format("%s%03d", SKU_PREFIX, i));
            sku.setDescription("Demo item " + i);
            sku.setStatus("ACTIVE");
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
        return new DemoResult(skuN, uomN, bcN, shipperN, huN);
    }

    /** Remove everything demo mode created (DEMO-prefixed master data); leave other config alone. */
    @Transactional
    public DemoResult disable() {
        int skuN = 0;
        int uomN = 0;
        int bcN = 0;
        for (Sku sku : skus.findAll()) {
            if (sku.getCode() != null && sku.getCode().startsWith(SKU_PREFIX)) {
                for (Barcode b : barcodes.findBySkuId(sku.getId())) {
                    barcodes.delete(b);
                    bcN++;
                }
                for (UnitOfMeasure u : uoms.findBySkuId(sku.getId())) {
                    uoms.delete(u);
                    uomN++;
                }
                skus.delete(sku);
                skuN++;
            }
        }

        int shipperN = 0;
        for (Shipper sh : shippers.findAll()) {
            if (sh.getCode() != null && sh.getCode().startsWith(SHIPPER_PREFIX)) {
                shippers.delete(sh);
                shipperN++;
            }
        }

        int huN = 0;
        var hu = huTypes.findByName(STORAGE_HU_NAME);
        if (hu.isPresent()) {
            huTypes.delete(hu.get());
            huN = 1;
        }

        setFlag(false);
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
