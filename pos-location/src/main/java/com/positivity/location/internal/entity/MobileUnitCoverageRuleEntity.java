package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
public class MobileUnitCoverageRuleEntity {

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

    @Column(name = "valid_from")
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "max_distance", precision = 10, scale = 2)
    private BigDecimal maxDistance;

}
