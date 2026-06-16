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
    // Configurable handheld process designer (process-designer service): viewing process
    // definitions + the operator process menu vs. authoring/publishing definitions.
    PROCESS_DESIGN_VIEW,
    PROCESS_DESIGN_EDIT,
    // Phase 3 sandboxed scripting escape hatch (spec §7.2): authoring a script task step. Distinct
    // from PROCESS_DESIGN_EDIT so the dangerous capability is granted narrowly (admin only by
    // default) and is defense-in-depth alongside the openwcs.process-designer.scripting.enabled flag.
    // Saving/publishing a definition that carries a script step requires BOTH this permission and the
    // flag; a sandboxed snippet still runs in a locked-down GraalJS context, never as host Java.
    PROCESS_SCRIPT_AUTHOR,
    IAM_ADMIN
}
