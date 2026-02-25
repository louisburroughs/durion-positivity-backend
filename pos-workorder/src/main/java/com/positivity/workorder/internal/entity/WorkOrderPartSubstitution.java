package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.SubstitutionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.PrePersist;
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
@EntityListeners(AuditingEntityListener.class)
public class WorkOrderPartSubstitution {

    @Id
    @GeneratedValue
    @UUIDv7Id
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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    private String reasonCode;

    @Column(columnDefinition = "TEXT")
    private String pricingSnapshot;

    @Enumerated(EnumType.STRING)
    private SubstitutionStatus status;

    @PrePersist
    public void prePersist() {
        if (selectedAt == null) {
            selectedAt = Instant.now();
        }
        if (createdAt == null) {
            createdAt = selectedAt;
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}
