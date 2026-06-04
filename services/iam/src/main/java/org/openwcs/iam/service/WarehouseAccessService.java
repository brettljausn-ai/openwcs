package org.openwcs.iam.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.openwcs.iam.api.Requests;
import org.openwcs.iam.api.WarehouseAccessView;
import org.openwcs.iam.domain.UserWarehouse;
import org.openwcs.iam.repo.UserWarehouseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user warehouse access (build.md §4.8): which warehouses a user may work in and their
 * default. The mapping is the source of truth for the gateway's warehouse-scope enforcement and
 * for the UI's warehouse switcher. Only admins write it (enforced at the controller).
 */
@Service
public class WarehouseAccessService {

    private final UserWarehouseRepository repository;

    public WarehouseAccessService(UserWarehouseRepository repository) {
        this.repository = repository;
    }

    /** The warehouse access for a single user. */
    @Transactional(readOnly = true)
    public WarehouseAccessView forUser(String username) {
        return toView(repository.findByUsername(username));
    }

    /** Just the allowed warehouse IDs for a user — the gateway's enforcement input. */
    @Transactional(readOnly = true)
    public List<UUID> allowedWarehouses(String username) {
        List<UUID> ids = new ArrayList<>();
        for (UserWarehouse uw : repository.findByUsername(username)) {
            ids.add(uw.getWarehouseId());
        }
        return ids;
    }

    /** Every user's warehouse access, keyed by username — for the admin screen. */
    @Transactional(readOnly = true)
    public Map<String, WarehouseAccessView> all() {
        Map<String, List<UserWarehouse>> byUser = new TreeMap<>();
        for (UserWarehouse uw : repository.findAll()) {
            byUser.computeIfAbsent(uw.getUsername(), k -> new ArrayList<>()).add(uw);
        }
        Map<String, WarehouseAccessView> out = new TreeMap<>();
        byUser.forEach((user, rows) -> out.put(user, toView(rows)));
        return out;
    }

    /**
     * Replace a user's warehouse access. The default (if given) must be one of the allowed
     * warehouses. Passing an empty list removes all access for the user.
     */
    @Transactional
    public WarehouseAccessView replaceForUser(String username, Requests.SetWarehouseAccess request) {
        // De-duplicate while preserving order.
        List<UUID> warehouses = new ArrayList<>(new LinkedHashSet<>(
                request.warehouses() == null ? List.of() : request.warehouses()));
        UUID def = request.defaultWarehouse();
        if (def != null && !warehouses.contains(def)) {
            throw new IllegalArgumentException("Default warehouse must be one of the allowed warehouses.");
        }

        repository.deleteByUsername(username);
        repository.flush(); // delete-then-insert on the same (username, warehouse_id) key in one tx
        List<UserWarehouse> rows = new ArrayList<>();
        for (UUID warehouseId : warehouses) {
            rows.add(new UserWarehouse(username, warehouseId, warehouseId.equals(def)));
        }
        repository.saveAll(rows);
        return toView(rows);
    }

    private static WarehouseAccessView toView(List<UserWarehouse> rows) {
        List<UUID> warehouses = new ArrayList<>();
        UUID def = null;
        for (UserWarehouse uw : rows) {
            warehouses.add(uw.getWarehouseId());
            if (uw.isDefault()) {
                def = uw.getWarehouseId();
            }
        }
        return new WarehouseAccessView(warehouses, def);
    }
}
