package org.openwcs.integration.host.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A processed host request keyed by its {@code Idempotency-Key}. On a repeat key the stored
 * response is replayed, so a host's retry never double-creates an order/ASN/adjustment.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", updatable = false, nullable = false)
    private String key;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "response_body")
    private String responseBody;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String key, int httpStatus, String responseBody) {
        this.key = key;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
    }

    public String getKey() {
        return key;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
