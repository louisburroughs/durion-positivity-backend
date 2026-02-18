package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
@Table(name = "location_price_override")
public class LocationPriceOverrideEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID locationId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID productId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal basePrice;

    @Column(precision = 19, scale = 4)
    private BigDecimal cost;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal overridePrice;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal discountPercent;

    @Column(precision = 10, scale = 4)
    private BigDecimal marginPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PriceOverrideStatus status;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID createdByUserId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(columnDefinition = "UUID")
    private UUID approvedByUserId;

    private Instant approvedAt;

    @Column(columnDefinition = "UUID")
    private UUID rejectedBy;

    private Instant rejectedAt;

    @Column(length = 128)
    private String rejectionReasonCode;

    @Column(length = 1024)
    private String rejectionNotes;

    private Instant resolvedAt;

    @jakarta.persistence.PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
