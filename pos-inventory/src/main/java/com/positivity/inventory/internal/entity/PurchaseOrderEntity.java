package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "purchase_order")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PurchaseOrderEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID purchaseOrderId;

    @Column(nullable = false)
    private UUID vendorId;

    @Column(nullable = false, unique = true)
    private String poNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PurchaseOrderStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Integer versionNumber = 1;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Long subtotalMinor;

    @Column(nullable = false)
    private Long taxMinor;

    @Column(nullable = false)
    private Long grandTotalMinor;

    private Long openBalanceMinor;

    private UUID shipToLocationId;

    private String paymentTermsId;

    private LocalDate poDate;

    private LocalDate expectedDeliveryDate;

    private String requestedBy;

    private String comment;

    private String approverId;

    private Instant approvalTimestamp;

    private String approvalNotes;

    private String encumbranceRef;

    @Column(nullable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PurchaseOrderLineEntity> lines = new ArrayList<>();
}
