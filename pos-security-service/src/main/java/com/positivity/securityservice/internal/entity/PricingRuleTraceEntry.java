package com.positivity.securityservice.internal.entity;


import java.util.UUID;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable rule-evaluation step tied to a pricing snapshot.
 *
 * Issue: #41
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "pricing_rule_trace_entries")
public class PricingRuleTraceEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private PricingSnapshot snapshot;

    @Column(name = "rule_id", nullable = false, length = 255, updatable = false)
    private String ruleId;

    @Column(name = "status", nullable = false, length = 32, updatable = false)
    private String status;

    @Column(name = "inputs", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String inputs;

    @Column(name = "outputs", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String outputs;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
