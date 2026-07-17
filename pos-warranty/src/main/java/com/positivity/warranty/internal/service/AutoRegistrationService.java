package com.positivity.warranty.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import org.jspecify.annotations.NonNull;

/**
 * Sale-driven warranty registration (issue #925): when an invoiced workorder sells a product
 * covered by an opt-in ({@code autoRegister}) {@code PRODUCT_LIST} policy, create the
 * {@code WarrantyRegistration} automatically so coverage exists without manual data entry.
 */
public interface AutoRegistrationService {

    /**
     * Creates registrations for a workorder sale fact. No-op unless the event carries an
     * {@code invoiceId} (invoice generated = sale concluded) and a {@code customerId}. One
     * registration per (policy, source invoice) regardless of quantity or how many matching
     * part lines the workorder has; replays and later facts for the same workorder are
     * deduped by an existence check.
     *
     * @return number of registrations created
     */
    int registerFromWorkorderSale(@NonNull WorkorderUpdatedV1 event);
}
