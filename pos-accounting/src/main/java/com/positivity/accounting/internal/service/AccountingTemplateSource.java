package com.positivity.accounting.internal.service;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A part of the accounting template that reaches a tenant as a whole or not at all (#2526).
 *
 * <p>The template is one body of rows in the platform tenant. A source says which of its entries
 * are its own and whether a given tenant receives them. {@link AccountingTemplateReader} is the
 * generic source every tenant receives; {@link RetreadPlantAddOnSource} is opt-in. A source whose
 * {@link #appliesTo} turns true for a tenant asks {@link AccountingTenantProvisioner#reconcile} to
 * bring that tenant up to date: entries are only ever added, so a source never turns off.
 */
public interface AccountingTemplateSource {

    /** A short name for logs. */
    @NonNull
    String name();

    /** True when {@code entry} belongs to this source. Every entry belongs to exactly one source. */
    boolean owns(AccountingTemplate.@NonNull Entry entry);

    /**
     * True when {@code tenantId} receives this source's entries. Called with that tenant bound and
     * inside its transaction, so an implementation may read the tenant's own rows.
     */
    boolean appliesTo(@NonNull UUID tenantId);
}
