package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An EDI invoice fact that cannot become a bill yet (CAP:550 S24, #2517; SPEC-accounting-workspace §9.2 "stored
 * durably even when the bill can't be created yet"). Written with the fact's processed mark in the handler transaction,
 * so the fact is never dropped. A {@link Reason#VENDOR_NOT_IN_COPY} hold is released, through the same bill creation,
 * once the vendor is copied; a {@link Reason#VENDOR_ID_MISSING} hold waits for a person (S25 turns it into a draft).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString(exclude = "payload")
@Entity
@Table(name = "supplier_invoice_hold")
public class SupplierInvoiceHold extends TenantScopedEntity {

    /** Why the fact is held. */
    public enum Reason {
        /** The fact names no pos-supplier vendor; waits for a person (S25). */
        VENDOR_ID_MISSING,
        /** The fact names a vendor accounting has not copied yet; released when it is. */
        VENDOR_NOT_IN_COPY
    }

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "hold_id", nullable = false, columnDefinition = "UUID")
    private UUID holdId;

    /** The fact's envelope event id. */
    @Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID eventId;

    /** The vendor's own invoice number. */
    @Column(name = "supplier_invoice_ref", length = 100, nullable = false, updatable = false)
    private String supplierInvoiceRef;

    @Column(name = "vendor_id", updatable = false)
    private UUID vendorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30, nullable = false, updatable = false)
    private Reason reason;

    /** The whole fact envelope as received (JSON text), so a release creates the bill from exactly what arrived. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    /** The bill the release created, or the original it was flagged against (S0). */
    @Column(name = "released_bill_id")
    private UUID releasedBillId;
}
