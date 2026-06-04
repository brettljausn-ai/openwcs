package org.openwcs.inventory.api;

/** Counts of demo rows created (or removed) by a demo seed / clear run. */
public record DemoSeedResult(int handlingUnits, int stockRows) {}
