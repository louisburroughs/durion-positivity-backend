package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "putaway_rule")
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PutawayRule {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID ruleId;

    @Column(nullable = false)
    private Integer priority;

    @Column(columnDefinition = "TEXT")
    private String criteria;

    @Column(nullable = false)
    private UUID destinationLocationId;

    /**
     * Destination-selection strategy (odoo-parity K2, issue #1055). Defaults to
     * {@link PutawayDestinationStrategy#FIXED} so pre-K2 rules keep suggesting
     * their configured {@code destinationLocationId} unchanged.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private PutawayDestinationStrategy destinationStrategy = PutawayDestinationStrategy.FIXED;

    @Column(nullable = false)
    @Builder.Default
    private boolean isEnabled = true;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
