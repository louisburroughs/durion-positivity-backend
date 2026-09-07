package com.positivity.catalog.internal.entity;

import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.enums.OperationCategory;
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
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Data-driven resolution precedence per (time type, source, operation category) — lower wins
 * (#1569, sourcing plan §3.4). Policy is data, not code: "tire ops prefer manufacturer install"
 * is a row edit here, not a release.
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "labor_time_source_policy")
public class LaborTimeSourcePolicyEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_type", nullable = false)
    private LaborTimeType timeType;

    @Column(name = "source_code", nullable = false)
    private String sourceCode;

    /**
     * Null applies the row to every operation category — the meaning every row carried before
     * V22 added the column. A row naming a category applies only to it, and beats a
     * category-less row there (#1569 residual R1).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_category")
    private OperationCategory operationCategory;

    @Column(name = "precedence", nullable = false)
    private int precedence;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
