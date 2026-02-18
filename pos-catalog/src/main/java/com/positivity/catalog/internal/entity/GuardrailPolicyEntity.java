package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "guardrail_policy")
public class GuardrailPolicyEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GuardrailPolicyScope scope;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID scopeId;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal minMarginPercent;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal maxDiscountPercent;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal autoApprovalThresholdPercent;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
