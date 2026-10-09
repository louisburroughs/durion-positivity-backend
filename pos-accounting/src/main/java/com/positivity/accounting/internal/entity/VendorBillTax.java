package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One tax type a vendor bill's document states, with its amount, as stated and never recalculated (CAP:550 S32d item
 * 10; closes G11). Signed like the bill's total: a credit note's amounts are negative. Written from the bill's
 * channel ({@link Source#DOCUMENT}: the EDI fact, the goods-receipt match request) or from the approval's {@code
 * taxByType[]} copied from the document ({@link Source#APPROVAL}).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "vendor_bill_tax",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_vendor_bill_tax_type",
                        columnNames = {"tenant_id", "vendor_bill_id", "tax_type"}))
public class VendorBillTax extends TenantScopedEntity {

    /** Where the amounts came from. */
    public enum Source {
        /** The bill's own document: the EDI fact or the match request. */
        DOCUMENT,
        /** Copied from the document by the person approving or accepting the bill. */
        APPROVAL
    }

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_bill_tax_id", nullable = false, columnDefinition = "UUID")
    private UUID vendorBillTaxId;

    @Column(name = "vendor_bill_id", nullable = false, updatable = false)
    private UUID vendorBillId;

    @Column(name = "tax_type", length = 32, nullable = false, updatable = false)
    private String taxType;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 20, nullable = false, updatable = false)
    private Source source;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** A stated amount of {@code taxType} on bill {@code vendorBillId}. */
    public static VendorBillTax of(UUID vendorBillId, String taxType, BigDecimal amount, Source source, Instant at) {
        VendorBillTax tax = new VendorBillTax();
        tax.setVendorBillId(vendorBillId);
        tax.setTaxType(taxType);
        tax.setAmount(amount);
        tax.setSource(source);
        tax.setCreatedAt(at);
        return tax;
    }
}
