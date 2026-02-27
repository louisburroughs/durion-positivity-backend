package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ReceivingLineStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "receiving_line")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@ToString(exclude = "session")
@EqualsAndHashCode(exclude = "session")
public class ReceivingLine {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ReceivingSession session;

    @Column(nullable = false, length = 255)
    private String productId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal expectedQuantity;

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal receivedQuantity = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private ReceivingLineStatus status = ReceivingLineStatus.EXPECTED;

    @Column(name = "workorder_id", length = 255)
    private String workorderId;

    @Column(name = "workorder_line_id", length = 255)
    private String workorderLineId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
