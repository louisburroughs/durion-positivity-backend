package com.positivity.inventory.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.positivity.inventory.internal.enums.DistributorExceptionReason;
import com.positivity.shared.id.UUIDv7Id;

import java.time.Instant;
import java.util.UUID;

/**
 * Exception queue entry for distributor feed records that cannot be normalized.
 *
 * Issue: CAP-170 (#47)
 */
@Entity
@Table(name = "distributor_feed_exception")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@SuppressWarnings("java:S2166")
/**
 * The business process sees this as an exception, but it's really just a record
 * of a failed normalization attempt, so it should not be treated as a true
 * exception in the code logic.
 */
public class DistributorFeedException {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID id;

    @Column(nullable = false)
    private String distributorId;

    private String distributorSku;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DistributorExceptionReason reason;

    @Column(nullable = false, length = 4000)
    private String rawPayload;
    /**
     * Timestamp when this configuration was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when this configuration was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
