package org.openwcs.common.security;

/**
 * The code-defined permission catalog (build.md §4.8). openWCS RBAC layers
 * users → roles → these coded permissions on top of Keycloak authentication. The
 * catalog is intentionally code-owned (not user-editable data); roles map to subsets
 * of it. Shared so every service and the IAM service reference the same codes.
 */
public enum Permission {
    MASTER_DATA_VIEW,
    MASTER_DATA_EDIT,
    INVENTORY_VIEW,
    STOCK_ADJUST,
    ORDER_VIEW,
    ORDER_CREATE,
    ORDER_RELEASE,
    ORDER_CANCEL,
    ORDER_SHIP,
    ORDER_POST_TRANSACTION,
    ALLOCATION_RUN,
    BATCH_BUILD,
    TXLOG_VIEW,
    TXLOG_APPEND,
    DEVICE_VIEW,
    DEVICE_OPERATE,
    IAM_ADMIN
}
