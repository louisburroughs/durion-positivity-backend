package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "guardrail_policy")
public class GuardrailPolicyEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
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

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (scope == null) {
            scope = GuardrailPolicyScope.LOCATION;
        }
    }
}
