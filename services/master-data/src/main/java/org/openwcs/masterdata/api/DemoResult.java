package org.openwcs.masterdata.api;

/** Counts of records created (on enable) or removed (on disable) by demo mode. */
public record DemoResult(int skus, int unitsOfMeasure, int barcodes, int shippers, int handlingUnitTypes) {
}
