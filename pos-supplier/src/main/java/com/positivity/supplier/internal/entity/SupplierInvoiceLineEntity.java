package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** One line of a fetched vendor invoice, as stated (CAP-321 #1227). */
@Entity
@Table(name = "supplier_invoice_line")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupplierInvoiceLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "supplier_invoice_line_id")
    private UUID supplierInvoiceLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_invoice_id", nullable = false)
    private SupplierInvoiceEntity invoice;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "article_ean", length = 64)
    private String articleEan;

    @Column(name = "supplier_article_code", length = 64)
    private String supplierArticleCode;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "unit_price", precision = 19, scale = 4)
    private BigDecimal unitPrice;

    /** The line's net amount as stated; never derived from quantity × price. */
    @Column(name = "line_amount", precision = 19, scale = 4)
    private BigDecimal lineAmount;

    @Column(name = "vendor_order_reference", length = 70)
    private String vendorOrderReference;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
