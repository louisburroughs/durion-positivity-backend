package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A vendor's re-issue, under the same number, of a bill already {@code APPROVED} (#2509; SPEC-accounting-workspace
 * §4.3 "approved bills are locked"). The approved bill keeps its status and approval; this exception item, linked to
 * it, records the incoming document and both amounts for a person to settle by credit note or with the vendor. One
 * row per supplier fact, never updated.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "vendor_bill_reissue",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_vendor_bill_reissue_source_event",
                        columnNames = {"tenant_id", "source_event_id"}))
public class VendorBillReissue extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_bill_reissue_id", nullable = false, columnDefinition = "UUID")
    private UUID vendorBillReissueId;

    /** The approved bill the re-issue names. */
    @Column(name = "vendor_bill_id", nullable = false, updatable = false)
    private UUID vendorBillId;

    @Column(name = "incoming_bill_number", length = 50, nullable = false, updatable = false)
    private String incomingBillNumber;

    @Column(name = "incoming_bill_date", nullable = false, updatable = false)
    private LocalDate incomingBillDate;

    /** The re-issue's amount, signed by its document type. */
    @Column(name = "incoming_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal incomingAmount;

    @Column(name = "incoming_currency_code", length = 3, nullable = false, updatable = false)
    private String incomingCurrencyCode;

    /** The approved bill's amount when the re-issue arrived. */
    @Column(name = "held_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal heldAmount;

    @Column(name = "held_currency_code", length = 3, nullable = false, updatable = false)
    private String heldCurrencyCode;

    /** The {@code supplier.invoice.received} fact that carried the re-issue. */
    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
