package com.positivity.customer.internal.entity;

import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "promotion_counter")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class PromotionCounter {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "counter_id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID counterId;

    @Column(name = "promotion_id", nullable = false, unique = true, columnDefinition = "UUID")
    private UUID promotionId;

    @Column(name = "total_usage_count", nullable = false)
    private int totalUsageCount = 0;

    @Version
    @Column(name = "version")
    private Long version;
}