package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.DistributorExceptionReason;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Exception queue entry for distributor feed records that cannot be normalized.
 */
@Entity
@Table(name = "distributor_feed_exception")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@SuppressWarnings("java:S2166")
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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
