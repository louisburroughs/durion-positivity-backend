package com.positivity.accounting.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxReferenceClient;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.config.PurchasePlace;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.TaxPurchaseRules;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import java.time.Clock;
import java.time.Duration;

/**
 * CAP:550 S43 test wiring: a real {@link VendorBillPurchaseTax} over a mocked pos-tax, for the made-up tax country
 * {@code ZZ} (not tax law). {@link #off()} answers {@code configured = false}, so neither the hold nor the accrual
 * applies, which is what every story before S43 assumed.
 */
final class PurchaseTaxFixtures {

    /** No purchase-tax rules for the country: neither behaviour applies. */
    static final TaxPurchaseRules OFF = new TaxPurchaseRules("ZZ", null, "STUB", false, "ALLOW", false);

    /** The AC fixture rule: hold tax on goods for resale, and self-assess untaxed expenses. */
    static final TaxPurchaseRules HOLD_AND_SELF_ASSESS = new TaxPurchaseRules("ZZ", null, "STUB", true, "HOLD", true);

    private PurchaseTaxFixtures() {}

    /** A purchase-tax component whose pos-tax answers {@link #OFF}, over an empty line repository. */
    static VendorBillPurchaseTax off() {
        TaxReferenceClient client = mock(TaxReferenceClient.class);
        when(client.purchaseRules(any(), any())).thenReturn(OFF);
        return purchaseTax(client, mock(SupplierVendorCopies.class), mock(VendorBillLineRepository.class));
    }

    /** A purchase-tax component over {@code client}, {@code vendorCopies} and {@code billLines}, country ZZ. */
    static VendorBillPurchaseTax purchaseTax(
            TaxReferenceClient client, SupplierVendorCopies vendorCopies, VendorBillLineRepository billLines) {
        return purchaseTax(client, vendorCopies, billLines, Clock.systemUTC());
    }

    /** As {@link #purchaseTax(TaxReferenceClient, SupplierVendorCopies, VendorBillLineRepository)}, on {@code clock}. */
    static VendorBillPurchaseTax purchaseTax(
            TaxReferenceClient client,
            SupplierVendorCopies vendorCopies,
            VendorBillLineRepository billLines,
            Clock clock) {
        return purchaseTax(client, vendorCopies, billLines, clock, "USD");
    }

    /** As above, with the ledger in {@code currency} (an ISO 4217 code); the country ZZ maps no currency. */
    static VendorBillPurchaseTax purchaseTax(
            TaxReferenceClient client,
            SupplierVendorCopies vendorCopies,
            VendorBillLineRepository billLines,
            Clock clock,
            String currency) {
        LedgerCurrency ledger = new LedgerCurrency(currency);
        return new VendorBillPurchaseTax(
                client,
                new TaxCountry("ZZ", ledger),
                new PurchasePlace("ZA", "00000"),
                ledger,
                vendorCopies,
                billLines,
                clock,
                Duration.ofMinutes(5));
    }
}
