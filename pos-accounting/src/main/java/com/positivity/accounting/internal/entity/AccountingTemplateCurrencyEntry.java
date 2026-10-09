package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Template data in the platform tenant (CAP:550 S32d item 3): a template entry a tenant receives only when its
 * functional currency is {@link #currencyCode}. Written by {@code R__seed_reference_accounting.sql} beside the entry
 * it names; read only by {@link com.positivity.accounting.internal.service.AccountingTemplateReader}.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Entity
@Table(name = "accounting_template_currency_entry")
public class AccountingTemplateCurrencyEntry extends TenantScopedEntity {

    @Id
    @AssignedIdentifier("template data seeded by Flyway: md5 of the platform tenant and the entry key, so a rerun of"
            + " the repeatable seed finds the same row")
    @Column(name = "currency_entry_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID currencyEntryId;

    @Column(name = "entry_key", length = 200, nullable = false, updatable = false)
    private String entryKey;

    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;
}
