package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Vendor Bill Line Item - individual line on a vendor bill.
 * Stores SKU, quantity, price for three-way matching against invoice and PO.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Vendor Bill</a>
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "vendor_bill_line",
        uniqueConstraints = @UniqueConstraint(columnNames = {"vendor_bill_id", "line_number"}),
        indexes = {
            @Index(name = "idx_vendor_bill_line_bill", columnList = "vendor_bill_id"),
            @Index(name = "idx_vendor_bill_line_product", columnList = "product_id")
        })
public class VendorBillLine extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "line_id", nullable = false, columnDefinition = "UUID")
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vendor_bill_id", nullable = false)
    private VendorBill vendorBill;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "quantity", precision = 19, scale = 4, nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 19, scale = 4, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal lineTotal;

    @Column(name = "is_inventory_item", nullable = false)
    private boolean isInventoryItem = true;

    /**
     * Quantity the vendor billed for this line (AW39), written by a match or a candidate selection: 0 for a received
     * line the invoice did not bill. Null while the bill has not been matched, when the received quantity is what is
     * billed.
     */
    @Column(name = "billed_quantity", precision = 19, scale = 4)
    private BigDecimal billedQuantity;

    /** Unit price the vendor billed for this line (AW39); null while the bill has not been matched. */
    @Column(name = "billed_unit_price", precision = 19, scale = 4)
    private BigDecimal billedUnitPrice;

    /** The billed quantity: the received quantity while the bill has not been matched. */
    public BigDecimal effectiveBilledQuantity() {
        return billedQuantity != null ? billedQuantity : quantity;
    }

    /** The billed unit price: the received price while the bill has not been matched. */
    public BigDecimal effectiveBilledUnitPrice() {
        return billedUnitPrice != null ? billedUnitPrice : unitPrice;
    }

    @Transient
    public UUID getVendorBillId() {
        return vendorBill != null ? vendorBill.getVendorBillId() : null;
    }

    public void setVendorBillId(UUID vendorBillId) {
        this.vendorBill = vendorBillId == null ? null : new VendorBill(vendorBillId);
    }
}
