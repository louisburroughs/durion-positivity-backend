package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "inventory_reservation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ReservationEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID reservationId;

    @Column(nullable = false, unique = true)
    private UUID workorderLineId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int requiredQuantity;

    @Column(nullable = false)
    @Builder.Default
    private int allocatedQuantity = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "reservation", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<AllocationEntity> allocations = new ArrayList<>();

}
