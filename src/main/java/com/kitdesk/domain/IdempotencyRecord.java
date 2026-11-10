package com.kitdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.domain.Persistable;

/**
 * The saved result of a request sent with an Idempotency-Key, scoped to the user who sent it.
 * A repeat with the same key and the same request gets this saved result back.
 */
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyRecord.Key.class)
public class IdempotencyRecord implements Persistable<IdempotencyRecord.Key> {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "idem_key")
    private String key;

    /** Hash of method + path + If-Match-Version + body, to spot the same key reused for another request. */
    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false)
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * A new record is always INSERTed, never merged into an existing row. If another request
     * already saved the same (user, key), the insert fails on the primary key instead of quietly
     * overwriting that request's saved result. This is what makes two requests sent at once with
     * the same key safe.
     */
    @Transient
    private boolean isNew = true;

    protected IdempotencyRecord() {
        // for JPA
    }

    public IdempotencyRecord(Long userId, String key, String requestHash, int responseStatus,
                             String responseBody, Instant createdAt) {
        this.userId = userId;
        this.key = key;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    /** Same key and same request? (If not, the contract says 422 IDEMPOTENCY_KEY_REUSED.) */
    public boolean matches(String otherRequestHash) {
        return requestHash.equals(otherRequestHash);
    }

    @Override
    public Key getId() {
        return new Key(userId, key);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        isNew = false;
    }

    public Long getUserId() { return userId; }
    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public int getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }

    /** Composite primary key: (user_id, idem_key). */
    public static class Key implements Serializable {
        private Long userId;
        private String key;

        protected Key() {
        }

        public Key(Long userId, String key) {
            this.userId = userId;
            this.key = key;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other && Objects.equals(userId, other.userId) && Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, key);
        }
    }
}
