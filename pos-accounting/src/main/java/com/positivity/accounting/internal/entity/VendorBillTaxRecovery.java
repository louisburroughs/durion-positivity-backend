package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
 * What a vendor bill's posting did with one stated tax type, for a recovery-enabled tenant (CAP:550 S32d item 10;
 * AW37-AW43, AW51, AW53): its regime and the amount recovered to {@code mappingKey}, or the reason nothing was. A bill
 * whose tax is not split has one row with no tax type ({@code TAX_SPLIT_MISSING}). Written with the posting, never
 * updated; amounts signed like the bill.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "vendor_bill_tax_recovery")
public class VendorBillTaxRecovery extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_bill_tax_recovery_id", nullable = false, columnDefinition = "UUID")
    private UUID vendorBillTaxRecoveryId;

    @Column(name = "vendor_bill_id", nullable = false, updatable = false)
    private UUID vendorBillId;

    @Column(name = "vendor_bill_gl_posting_id", nullable = false, updatable = false)
    private UUID vendorBillGlPostingId;

    @Column(name = "tax_type", length = 32, updatable = false)
    private String taxType;

    @Column(name = "regime", length = 32, updatable = false)
    private String regime;

    @Column(name = "stated_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal statedAmount;

    @Column(name = "recovered_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal recoveredAmount;

    @Column(name = "mapping_key", length = 100, updatable = false)
    private String mappingKey;

    @Column(name = "recovery_withheld_reason", length = 40, updatable = false)
    private String recoveryWithheldReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
