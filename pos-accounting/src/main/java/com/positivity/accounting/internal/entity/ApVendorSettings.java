package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.VendorBillDebitClass;
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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * What stays accounting's about a pos-supplier vendor (CAP:550 S24, #2517; SPEC-accounting-workspace §4.9 "What stays
 * accounting's"; AW23, AW39): the confirmation of a changed remit-to, the vendor's AP defaults, its AP payment hold
 * and its information-return reportable flag (#2615). One row per vendor, created on the first write; each change is
 * an {@code accounting_audit_log} row (entity {@code VENDOR}).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@ToString
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "ap_vendor_settings",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ap_vendor_settings_tenant_vendor_key",
                        columnNames = {"tenant_id", "vendor_id"}))
public class ApVendorSettings extends TenantScopedEntity {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "ap_vendor_settings_id", nullable = false, columnDefinition = "UUID")
    private UUID apVendorSettingsId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    /** The remit-to version someone confirmed; a payment by anyone else passes on it while it is current. */
    @Column(name = "confirmed_remit_to_version")
    private Integer confirmedRemitToVersion;

    @Column(name = "remit_to_confirmed_by", length = 255)
    private String remitToConfirmedBy;

    @Column(name = "remit_to_confirmed_at")
    private Instant remitToConfirmedAt;

    /** How the confirmer verified the new remit-to. */
    @Column(name = "remit_to_confirmation_justification", length = 1000)
    private String remitToConfirmationJustification;

    /** {@code GOODS} or {@code EXPENSE}: the class of a bill whose lines are not stored, when nobody chose one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_debit_class", length = 20)
    private VendorBillDebitClass defaultDebitClass;

    /** An active {@code VENDOR_BILL} key {@code EXPENSE_<CODE>}, for {@code EXPENSE} and non-stock lines. */
    @Column(name = "default_expense_mapping_key", length = 100)
    private String defaultExpenseMappingKey;

    /**
     * The AP payment hold (#2615): true refuses an AP payment to the vendor (422 {@code VENDOR_ON_AP_HOLD}); approval
     * and posting go ahead. Independent of the vendor's status.
     */
    @Column(name = "ap_hold", nullable = false)
    private boolean apHold;

    /** Why the vendor is held, 10-500 characters; CONFIDENTIAL (ADR-0072), so it never reaches toString or a log. */
    @ToString.Exclude
    @Column(name = "ap_hold_reason", length = 500)
    private String apHoldReason;

    /** Who set the hold or last changed its reason (the principal name, ADR-0018). */
    @Column(name = "ap_hold_set_by", length = 255)
    private String apHoldSetBy;

    /** When the hold was set or its reason last changed. */
    @Column(name = "ap_hold_set_at")
    private Instant apHoldSetAt;

    /** Whether the vendor's payments are reportable on the tax country's information return (#2615). */
    @Column(name = "information_return_reportable", nullable = false)
    private boolean informationReturnReportable;

    /** A form code of pos-tax's information-return configuration for the tax country; null when not reportable. */
    @Column(name = "information_return_form", length = 32)
    private String informationReturnForm;

    /** A box code of that form; null when not reportable. */
    @Column(name = "information_return_box", length = 10)
    private String informationReturnBox;

    /** The payee-id scheme the payee is reported under, one of the form's; never the number itself. */
    @Column(name = "information_return_payee_scheme", length = 16)
    private String informationReturnPayeeScheme;

    /**
     * Whether a bill of this vendor charging tax on goods for resale is approved without a per-bill override where
     * the tax country's purchase-tax rule holds such bills (CAP:550 S43, AW44). Honoured at the next decision.
     */
    @Column(name = "accept_tax_on_resale_goods", nullable = false)
    private boolean acceptTaxOnResaleGoods;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** ADR-0024: when the row was written first. */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** ADR-0024: when the row last changed. */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
