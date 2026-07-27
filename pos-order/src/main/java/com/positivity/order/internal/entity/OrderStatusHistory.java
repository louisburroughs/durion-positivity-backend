package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Append-only record of every {@link SalesOrder} status transition, written by
 * {@code OrderStateMachine} in the same transaction as the transition itself.
 */
@Entity
@Table(name = "order_status_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusHistory {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID historyId;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID orderId;

    /** Null for the initial DRAFT entry recorded at cart creation. */
    @Enumerated(EnumType.STRING)
    @Column
    private SalesOrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SalesOrderStatus toStatus;

    @Column(nullable = false)
    private String actor;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant transitionedAt;
}
