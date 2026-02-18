package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "location_guardrail_policy")
public class LocationGuardrailPolicyEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false, columnDefinition = "UUID", unique = true)
    private UUID locationId;

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
