package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.UUID;

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
@Table(name = "vendor_bill_line", uniqueConstraints = @UniqueConstraint(columnNames = { "vendor_bill_id",
        "line_number" }), indexes = {
                @Index(name = "idx_vendor_bill_line_bill", columnList = "vendor_bill_id"),
                @Index(name = "idx_vendor_bill_line_product", columnList = "product_id")
        })
public class VendorBillLine {

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

        @Transient
        public UUID getVendorBillId() {
                return vendorBill != null ? vendorBill.getVendorBillId() : null;
        }

        public void setVendorBillId(UUID vendorBillId) {
                this.vendorBill = vendorBillId == null ? null : new VendorBill(vendorBillId);
        }
}
