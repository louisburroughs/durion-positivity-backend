package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Immutable record of a workorder part substitution action.
 *
 * Issue: #49
 */
@Entity
@Table(name = "workorder_part_substitution")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrderPartSubstitution {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID substitutionId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID workorderId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID workorderLineItemId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID originalProductId;

    private String originalPartNumberSnapshot;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID substituteProductId;

    private String substitutePartNumberSnapshot;

    @Column(nullable = false)
    private String selectedBy;

    @Column(nullable = false)
    private Instant selectedAt;

    private String reasonCode;

    @Column(columnDefinition = "TEXT")
    private String pricingSnapshot;

    @Enumerated(EnumType.STRING)
    private SubstitutionStatus status;

    @PrePersist
    public void prePersist() {
        if (substitutionId == null) {
            substitutionId = UUIDv7Generator.generate();
        }
        if (selectedAt == null) {
            selectedAt = Instant.now();
        }
    }
}
