package org.openwcs.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One storage block's physical fill level on one day: how many of the block's cells
 * (master-data locations) held any stock row or handling unit. Written daily by the
 * ShedLock-guarded sweeper and on demand by the storage-density report endpoint, so
 * the Reporting screen can show a 90-day occupancy history per block.
 */
@Entity
@Table(name = "storage_density_snapshot")
public class StorageDensitySnapshot extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "snapshot_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** master-data storage block (no cross-service FK). */
    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "occupied_cells", nullable = false)
    private int occupiedCells;

    @Column(name = "total_cells", nullable = false)
    private int totalCells;

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID getBlockId() {
        return blockId;
    }

    public void setBlockId(UUID blockId) {
        this.blockId = blockId;
    }

    public LocalDate getDay() {
        return day;
    }

    public void setDay(LocalDate day) {
        this.day = day;
    }

    public int getOccupiedCells() {
        return occupiedCells;
    }

    public void setOccupiedCells(int occupiedCells) {
        this.occupiedCells = occupiedCells;
    }

    public int getTotalCells() {
        return totalCells;
    }

    public void setTotalCells(int totalCells) {
        this.totalCells = totalCells;
    }
}
