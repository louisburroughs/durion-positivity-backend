package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One operation's membership of a service package (#1575 Tier 0, T0-4).
 *
 * <p>{@link #required} is what separates a fleet requirement from an upsell: a required member
 * is work the package includes by definition, and a fleet requirement set is a package whose
 * members are required. An optional member is something the package offers.
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service_package_member")
public class ServicePackageMemberEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "package_id", nullable = false)
    private UUID packageId;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    /** Presentation and work order within the package. */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    /** How many of this operation the package includes — two tyre repairs, not two memberships. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(nullable = false)
    private boolean required = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
