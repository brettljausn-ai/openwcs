package org.openwcs.masterdata.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.masterdata.api.SkuSyncRequest;
import org.openwcs.masterdata.domain.Barcode;
import org.openwcs.masterdata.domain.Sku;
import org.openwcs.masterdata.domain.UnitOfMeasure;
import org.openwcs.masterdata.repo.BarcodeRepository;
import org.openwcs.masterdata.repo.BarcodeTypeRepository;
import org.openwcs.masterdata.repo.SkuRepository;
import org.openwcs.masterdata.repo.UnitOfMeasureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Host-driven SKU sync: upserts each SKU by code and fully reconciles its unit-of-measure
 * hierarchy and barcodes from the inline payload (build.md §6, §16). The host is authoritative,
 * so a SKU's stored UoMs/barcodes that are absent from the push are deleted; the rest are
 * upserted. UoMs are matched/kept by {@code (sku, code)} so internal ids (and any stock referencing
 * them) survive a re-sync; intra-SKU references (UoM parent, barcode UoM) are resolved by code.
 *
 * <p>The whole batch runs in one transaction, so a push that fails on any SKU rolls back entirely
 * rather than leaving a partially-applied catalog.
 */
@Service
public class SkuSyncService {

    private final SkuRepository skus;
    private final UnitOfMeasureRepository uoms;
    private final BarcodeRepository barcodes;
    private final BarcodeTypeRepository barcodeTypes;

    public SkuSyncService(SkuRepository skus, UnitOfMeasureRepository uoms,
                          BarcodeRepository barcodes, BarcodeTypeRepository barcodeTypes) {
        this.skus = skus;
        this.uoms = uoms;
        this.barcodes = barcodes;
        this.barcodeTypes = barcodeTypes;
    }

    /** Outcome of syncing one SKU. */
    public enum Action { CREATED, UPDATED }

    /** Per-SKU result of a sync batch. */
    public record SkuResult(String code, Action action, int uoms, int barcodes) {
    }

    /** Summary of a sync batch. */
    public record SyncReport(int received, int created, int updated, List<SkuResult> results) {
    }

    @Transactional
    public SyncReport sync(List<SkuSyncRequest> requests) {
        List<SkuResult> results = new ArrayList<>();
        int created = 0;
        int updated = 0;
        for (SkuSyncRequest req : requests) {
            SkuResult r = syncOne(req);
            if (r.action() == Action.CREATED) {
                created++;
            } else {
                updated++;
            }
            results.add(r);
        }
        return new SyncReport(requests.size(), created, updated, results);
    }

    private SkuResult syncOne(SkuSyncRequest req) {
        Optional<Sku> existing = skus.findByCode(req.code());
        Sku sku = existing.orElseGet(Sku::new);
        Action action = existing.isPresent() ? Action.UPDATED : Action.CREATED;

        sku.setCode(req.code());
        sku.setDescription(req.description());
        sku.setOwnerClient(req.ownerClient());
        sku.setBatchTracked(Boolean.TRUE.equals(req.batchTracked()));
        sku.setSerialTracked(Boolean.TRUE.equals(req.serialTracked()));
        sku.setDateTracked(Boolean.TRUE.equals(req.dateTracked()));
        Sku saved = skus.save(sku);
        UUID skuId = saved.getId();

        // Clear barcodes first so removing a UoM below never trips the barcode→UoM foreign key;
        // they are fully rebuilt from the payload once the UoM set (and its ids) is settled.
        barcodes.deleteBySkuId(skuId);
        barcodes.flush();

        Map<String, UUID> uomIdByCode = reconcileUoms(skuId, req.uoms());
        int barcodeCount = createBarcodes(skuId, req.barcodes(), uomIdByCode);
        return new SkuResult(req.code(), action, uomIdByCode.size(), barcodeCount);
    }

    /** Replace the SKU's UoM set from the payload; returns the resulting code → id map. */
    private Map<String, UUID> reconcileUoms(UUID skuId, List<SkuSyncRequest.UomSync> desired) {
        List<SkuSyncRequest.UomSync> wanted = desired == null ? List.of() : desired;
        List<UnitOfMeasure> stored = uoms.findBySkuId(skuId);

        // Break self-referencing parent links up front so deletes below never hit the FK.
        for (UnitOfMeasure u : stored) {
            u.setParentUomId(null);
        }
        uoms.saveAll(stored);
        uoms.flush();

        Map<String, UnitOfMeasure> storedByCode = stored.stream()
                .collect(Collectors.toMap(UnitOfMeasure::getCode, u -> u, (a, b) -> a));
        Set<String> wantedCodes = wanted.stream()
                .map(SkuSyncRequest.UomSync::code).collect(Collectors.toSet());

        // Remove UoMs the host no longer lists. The SKU's barcodes were already cleared by the
        // caller and parent links nulled above, so neither FK (barcode→UoM, UoM→parent) can block.
        for (UnitOfMeasure u : stored) {
            if (!wantedCodes.contains(u.getCode())) {
                uoms.delete(u);
            }
        }
        uoms.flush();

        // First pass: upsert fields (reusing the kept row so its id survives), no parent yet.
        Map<String, UnitOfMeasure> byCode = new HashMap<>();
        for (SkuSyncRequest.UomSync d : wanted) {
            UnitOfMeasure u = storedByCode.getOrDefault(d.code(), new UnitOfMeasure());
            u.setSkuId(skuId);
            u.setCode(d.code());
            u.setQtyInParent(d.qtyInParent());
            u.setLengthMm(d.lengthMm());
            u.setWidthMm(d.widthMm());
            u.setHeightMm(d.heightMm());
            u.setWeightG(d.weightG());
            u.setBaseUnit(Boolean.TRUE.equals(d.baseUnit()));
            byCode.put(d.code(), uoms.save(u));
        }
        uoms.flush();

        // Second pass: link parents now that every row has an id.
        Map<String, UUID> idByCode = new HashMap<>();
        for (SkuSyncRequest.UomSync d : wanted) {
            UnitOfMeasure u = byCode.get(d.code());
            if (d.parentCode() != null) {
                UnitOfMeasure parent = byCode.get(d.parentCode());
                u.setParentUomId(parent == null ? null : parent.getId());
                uoms.save(u);
            }
            idByCode.put(d.code(), u.getId());
        }
        return idByCode;
    }

    /**
     * Create the SKU's barcodes from the payload; UoM and type resolved by code/name. The SKU's old
     * barcodes are cleared by the caller before the UoM set is reconciled, so this only inserts.
     */
    private int createBarcodes(UUID skuId, List<SkuSyncRequest.BarcodeSync> desired,
                               Map<String, UUID> uomIdByCode) {
        if (desired == null) {
            return 0;
        }
        for (SkuSyncRequest.BarcodeSync d : desired) {
            Barcode b = new Barcode();
            b.setSkuId(skuId);
            b.setValue(d.value());
            b.setUomId(d.uomCode() == null ? null : uomIdByCode.get(d.uomCode()));
            b.setBarcodeTypeId(d.type() == null ? null
                    : barcodeTypes.findByName(d.type()).map(t -> t.getId()).orElse(null));
            barcodes.save(b);
        }
        return desired.size();
    }
}
