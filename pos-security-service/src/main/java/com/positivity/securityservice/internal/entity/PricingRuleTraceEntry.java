package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable rule-evaluation step tied to a pricing snapshot.
 *
 * Issue: #41
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "pricing_rule_trace_entries")
public class PricingRuleTraceEntry {

    @Id
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

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }
}
