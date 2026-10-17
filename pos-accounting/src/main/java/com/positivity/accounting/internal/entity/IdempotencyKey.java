package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Entity for tracking idempotency keys to prevent duplicate payment processing.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "idempotency_keys",
        indexes = {@Index(name = "idx_key_value", columnList = "keyValue", unique = true)})
public class IdempotencyKey extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, unique = true)
    private String keyValue;

    @Column(nullable = false)
    private UUID invoiceId;

    @Column(nullable = false)
    @CreatedDate
    private Instant createdAt;

    /**
     * Historical only (#2595): posting keys never expire, new rows carry null, and nothing reads or deletes by it. Rows
     * written before V28 keep the value they were given; they are honoured all the same.
     */
    @Column
    private Instant expiresAt;

    public IdempotencyKey(String keyValue, UUID invoiceId) {
        this.keyValue = keyValue;
        this.invoiceId = invoiceId;
    }
}
