package org.openwcs.masterdata.api;

/**
 * Demo-mode status. {@code canEnable} is true only when the catalog is empty (no host data),
 * since demo mode may only seed onto a fresh, host-free system.
 */
public record DemoStatusView(boolean enabled, boolean canEnable, long skuCount) {
}
