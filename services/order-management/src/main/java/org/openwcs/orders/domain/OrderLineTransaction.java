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

    /** Handheld process instance that drove this posting (null for direct/non-process callers). */
    @Column(name = "process_instance_id")
    private String processInstanceId;

    /** Order type this posting was made under (INBOUND / OUTBOUND / COUNT / ADJUSTMENT), for traceability. */
    @Column(name = "order_type")
    private String orderType;

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

    /** Set by the outbox relay once the event has been appended to the transaction log. */
    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getActor() {
        return actor;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    /** Stamp the handheld process instance that drove this posting (audit / traceability). */
    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getOrderType() {
        return orderType;
    }

    /** Stamp the order type this posting was made under (audit / traceability). */
    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
