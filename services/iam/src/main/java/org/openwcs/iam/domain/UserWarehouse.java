package org.openwcs.iam.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One warehouse a user may work in. The pair {@code (username, warehouseId)} is the key; at most
 * one row per user has {@code isDefault=true} (enforced by a partial unique index, V4). Warehouses
 * live in master-data, so they're referenced by UUID only.
 */
@Entity
@Table(name = "user_warehouse")
@IdClass(UserWarehouse.Key.class)
public class UserWarehouse {

    @Id
    @Column(name = "username", nullable = false, updatable = false)
    private String username;

    @Id
    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    public UserWarehouse() {
    }

    public UserWarehouse(String username, UUID warehouseId, boolean isDefault) {
        this.username = username;
        this.warehouseId = warehouseId;
        this.isDefault = isDefault;
    }

    public String getUsername() {
        return username;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    /** Composite primary key for {@link UserWarehouse}. */
    public static class Key implements Serializable {
        private String username;
        private UUID warehouseId;

        public Key() {
        }

        public Key(String username, UUID warehouseId) {
            this.username = username;
            this.warehouseId = warehouseId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(username, key.username) && Objects.equals(warehouseId, key.warehouseId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(username, warehouseId);
        }
    }
}
