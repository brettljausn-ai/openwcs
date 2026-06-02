package org.openwcs.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One stock posting beneath an order line: a receipt, pick, count, or adjustment. The
 * matching event appended to the transaction log is referenced by {@code eventId}; the
 * physical stock effect is applied by the inventory projection (build.md §5.4, ADR 0002).
 */
@Entity
@Table(name = "order_line_transaction")
public class OrderLineTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "txn_id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    private OrderLine line;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false)
    private TransactionType txnType;

    /** Line-progress contribution; signed for COUNT/ADJUSTMENT. */
    @Column(name = "qty", nullable = false)
    private BigDecimal qty;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "hu_id")
    private UUID huId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "actor")
    private String actor;

    @CreationTimestamp
    @Column(name = "posted_at", updatable = false, nullable = false)
    private Instant postedAt;

    protected OrderLineTransaction() {
    }

    public OrderLineTransaction(OrderLine line, TransactionType txnType, BigDecimal qty,
                                UUID locationId, UUID huId, UUID batchId, UUID eventId, String actor) {
        this.line = line;
        this.txnType = txnType;
        this.qty = qty;
        this.locationId = locationId;
        this.huId = huId;
        this.batchId = batchId;
        this.eventId = eventId;
        this.actor = actor;
    }

    public UUID getId() {
        return id;
    }

    public OrderLine getLine() {
        return line;
    }

    public void setLine(OrderLine line) {
        this.line = line;
    }

    public TransactionType getTxnType() {
        return txnType;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public UUID getHuId() {
        return huId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getActor() {
        return actor;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
