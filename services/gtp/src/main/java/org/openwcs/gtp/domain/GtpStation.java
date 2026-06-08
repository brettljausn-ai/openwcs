package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A goods-to-person workplace/station. {@code mode} documents the physical realisation of its
 * order destinations — {@code ORDER_LOCATION} (order HUs in fixed/conveyor locations) or
 * {@code PUT_WALL} (a rack of lit cubbies, typical for AMR goods-to-rack); it is the destination
 * <em>topology</em> and is unchanged by operating modes.
 *
 * <p>{@code supportedModes} is the orthogonal set of {@link OperatingMode operating modes} the
 * station can run (what the operator does with a presented HU) — stored as a comma-separated set,
 * mirroring the simple text style of {@code mode}. Defaults to {@code PICKING}.
 */
@Entity
@Table(name = "gtp_station")
public class GtpStation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "gtp_station_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    /** Optional human-readable display name (e.g. "Aisle 3 Put-wall"). */
    @Column(name = "name")
    private String name;

    /** ORDER_LOCATION | PUT_WALL. */
    @Column(name = "mode", nullable = false)
    private String mode = "ORDER_LOCATION";

    /** Comma-separated set of supported {@link OperatingMode}s; at least PICKING. */
    @Column(name = "supported_modes", nullable = false)
    private String supportedModes = OperatingMode.PICKING.name();

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    /** Max HUs with an active PICKING transport inbound to this station at once. */
    @Column(name = "max_in_transit_picking", nullable = false)
    private int maxInTransitPicking = 4;

    /** Max HUs with an active non-PICKING (other) transport inbound to this station at once. */
    @Column(name = "max_in_transit_other", nullable = false)
    private int maxInTransitOther = 2;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getSupportedModes() {
        return supportedModes;
    }

    public void setSupportedModes(String supportedModes) {
        this.supportedModes = supportedModes;
    }

    /** The supported operating modes as a parsed, ordered set. */
    public Set<OperatingMode> supportedModeSet() {
        if (supportedModes == null || supportedModes.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(supportedModes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(OperatingMode::parse)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Store a supported-modes set as the comma-separated column value (insertion order). */
    public void setSupportedModeSet(Set<OperatingMode> modes) {
        this.supportedModes = modes.stream().map(OperatingMode::name).collect(Collectors.joining(","));
    }

    public boolean supports(OperatingMode mode) {
        return supportedModeSet().contains(mode);
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getMaxInTransitPicking() {
        return maxInTransitPicking;
    }

    public void setMaxInTransitPicking(int maxInTransitPicking) {
        this.maxInTransitPicking = maxInTransitPicking;
    }

    public int getMaxInTransitOther() {
        return maxInTransitOther;
    }

    public void setMaxInTransitOther(int maxInTransitOther) {
        this.maxInTransitOther = maxInTransitOther;
    }
}
