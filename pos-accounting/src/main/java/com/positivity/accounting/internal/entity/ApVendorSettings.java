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
 * accounting's"; AW23, AW39): the confirmation of a changed remit-to and the vendor's AP defaults. One row per vendor,
 * created on the first write; each change is an {@code accounting_audit_log} row (entity {@code VENDOR}).
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
