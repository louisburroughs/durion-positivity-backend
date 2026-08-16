package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A vendor AR document as fetched (CAP-321 #1227).
 *
 * <p>Persisted before it is published, so the fact has something durable behind it and a consumer
 * that asks "what did the vendor actually send" has an answer that is not a Kafka retention window.
 *
 * <p>Amounts are the vendor's, stored as sent. Nothing recalculates them, including where the lines
 * do not sum to the total: that disagreement is what an AP clerk is employed to notice, and
 * reconciling it here would destroy the evidence before anyone saw it.
 */
@Entity
@Table(name = "supplier_invoice")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierInvoiceEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "supplier_invoice_id")
    private UUID supplierInvoiceId;

    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    /** The profile's alias at fetch time — a snapshot for reading, never a key. */
    @Column(name = "supplier_ref", nullable = false, length = 100)
    private String supplierRef;

    @Column(name = "vendor_invoice_number", nullable = false, length = 70)
    private String vendorInvoiceNumber;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    /** {@code INVOICE} or {@code CREDIT_NOTE}: which way the money runs. */
    @Column(name = "invoice_type", nullable = false, length = 16)
    private String invoiceType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "total_net_amount", precision = 19, scale = 4)
    private BigDecimal totalNetAmount;

    @Column(name = "total_tax_amount", precision = 19, scale = 4)
    private BigDecimal totalTaxAmount;

    @Column(name = "total_gross_amount", precision = 19, scale = 4)
    private BigDecimal totalGrossAmount;

    @Column(name = "vendor_order_reference", length = 70)
    private String vendorOrderReference;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SupplierInvoiceLineEntity> lines = new ArrayList<>();
}
