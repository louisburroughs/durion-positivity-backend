package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.InventoryVarianceType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "inventory_variance")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventoryVariance {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID varianceId;

    @ManyToOne(optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ReceivingSession session;

    @ManyToOne(optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private ReceivingLine line;

    @Column(nullable = false, length = 255)
    private String productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private InventoryVarianceType varianceType;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal varianceQuantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal expectedQuantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal receivedQuantity;

    @Column(nullable = false, length = 255)
    private String recordedByUserId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
