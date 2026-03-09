package com.positivity.price.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.price.internal.enums.EvaluationContext;
import com.positivity.price.internal.enums.OverrideStatus;
import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "restriction_override_audit")
public class RestrictionOverrideAudit {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID overrideId;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID ruleId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID transactionId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EvaluationContext overrideContext;

    @Column(nullable = false)
    private String reasonCode;

    @Column
    private String notes;

    @Column
    private String approvedBy;

    @Column(nullable = false)
    private Integer policyVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OverrideStatus status;

    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
