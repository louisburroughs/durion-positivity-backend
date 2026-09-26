package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
 * Coverage-rule row associated with a mobile unit.
 *
 * Issue: #76
 */
@Entity
@Table(name = "mobile_unit_coverage_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class MobileUnitCoverageRuleEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mobile_unit_id", nullable = false)
    private MobileUnitEntity mobileUnit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_area_id")
    private ServiceAreaEntity serviceArea;

    @Column(name = "rule_type", length = 20)
    private String ruleType;

    @Column(nullable = false)
    private Integer priority;

    /**
     * Start of the window, inclusive (DECISION-LOCATION-017, DECISION-LOCATION-027 rule 4): a UTC
     * instant, not a calendar date. {@code null} means "always has started".
     */
    @Column(name = "valid_from")
    private Instant validFrom;

    /**
     * End of the window, exclusive: the rule matches up to but not including this instant. {@code
     * null} means "never ends". Migration V9 converted the former {@code date} column so that the
     * old inclusive end-of-day reads unchanged: the day after the old {@code valid_to} date, at UTC
     * midnight.
     */
    @Column(name = "valid_to")
    private Instant validTo;

    /** Canonical kilometres (DECISION-LOCATION-028); converted to and from the caller's unit at the edge. */
    @Column(name = "max_distance_km", precision = 10, scale = 2)
    private BigDecimal maxDistanceKm;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
