package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What the vendor-bill read tells the client about tax, beyond the bill's own data (CAP:550 #2679): the configured
 * labels of its tax types and regimes, and the tax types an approver may split the stated tax into (AW51). Both come
 * from pos-tax's configuration for the tenant's tax country, read once per response and only when a row needs it;
 * neither is ever invented. No code here names a country, regime or tax type.
 */
@Component
@RequiredArgsConstructor
public class VendorBillTaxGuidance {

    /** The statuses in which an approver may still enter the split: the bill is approvable. */
    static final Set<VendorBillStatus> APPROVABLE =
            EnumSet.of(VendorBillStatus.AWAITING_APPROVAL, VendorBillStatus.MATCH_EXCEPTION);

    private final TaxProfileReads profiles;
    private final TaxCountry taxCountry;
    private final InputTaxRecoveryFlags recoveryFlags;
    private final VendorBillPostingService postingService;

    /** The tax country's profile for its labels; empty when pos-tax cannot answer (see {@link TaxProfileReads}). */
    public Optional<TaxProfileClient.TaxTypes> profile() {
        return profiles.taxTypesIfAvailable(taxCountry.code());
    }

    /**
     * The tax types the approver may split {@code bill}'s stated tax into: those of the tax country's regimes the
     * tenant is registered for on the date the approval would post on (AW49), while recovery is on, which is the
     * country's currency being the functional currency (ADR-0067). Empty when the bill is not approvable, states no
     * tax, or no such regime; null when the profile cannot be read now (unknown, ADR-0017). Only the tax country is
     * asked about: another country's registration never decides this, and no second call to pos-tax is made.
     */
    public @Nullable List<VendorBillReview.TaxTypeChoice> choices(
            @NonNull VendorBill bill, @NonNull Supplier<Optional<TaxProfileClient.TaxTypes>> profile) {
        if (!APPROVABLE.contains(bill.getStatus()) || !statesTax(bill)) {
            return List.of();
        }
        String country = taxCountry.code();
        LocalDate postingDate = postingService.postingDate(bill).date();
        Set<String> registeredRegimes = recoveryFlags.regimesUnchecked(postingDate).stream()
                .filter(flag -> country.equals(flag.countryCode()) && flag.registration() != null)
                .map(InputTaxRecoveryFlags.RegimeFlag::regime)
                .collect(Collectors.toSet());
        if (registeredRegimes.isEmpty()) {
            return List.of();
        }
        Optional<TaxProfileClient.TaxTypes> read = profile.get();
        if (read.isEmpty()) {
            return null;
        }
        String currency = read.get().currency();
        if (currency == null || !currency.equals(recoveryFlags.functionalCurrency())) {
            return List.of();
        }
        return read.get().taxTypes().stream()
                .filter(type -> type.regime() != null && registeredRegimes.contains(type.regime()))
                .map(type -> new VendorBillReview.TaxTypeChoice(type.taxType(), type.label()))
                .toList();
    }

    private static boolean statesTax(VendorBill bill) {
        return bill.getTaxAmount() != null && bill.getTaxAmount().signum() != 0;
    }
}
