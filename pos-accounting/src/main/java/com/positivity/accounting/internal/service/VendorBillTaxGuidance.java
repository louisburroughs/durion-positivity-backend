package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.config.TaxCountry;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What the vendor-bill read tells the client about tax, beyond the bill's own data (CAP:550 #2679): the configured
 * labels of its tax types and regimes, and the tax types an approver may split the stated tax into (AW51). Both come
 * from pos-tax's configuration for the tenant's tax country; neither is ever invented. No code here names a country,
 * regime or tax type.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VendorBillTaxGuidance {

    /** The statuses in which an approver may still enter the split: the bill is approvable. */
    static final Set<VendorBillStatus> APPROVABLE =
            EnumSet.of(VendorBillStatus.AWAITING_APPROVAL, VendorBillStatus.MATCH_EXCEPTION);

    private final TaxProfileClient taxProfiles;
    private final TaxCountry taxCountry;
    private final InputTaxRecoveryFlags recoveryFlags;
    private final VendorBillPostingService postingService;

    /**
     * The tax country's profile, for its labels; empty when pos-tax cannot answer, so a label is left null rather
     * than invented. A read never fails for want of a label.
     */
    public @NonNull Optional<TaxProfileClient.TaxTypes> profile() {
        try {
            return Optional.ofNullable(taxProfiles.taxTypes(taxCountry.code()));
        } catch (TaxServiceUnavailableException e) {
            log.warn("Vendor-bill read without the tax labels: the tax service did not answer");
            return Optional.empty();
        }
    }

    /**
     * The tax types the approver may split {@code bill}'s stated tax into: those of the regimes whose recovery is on
     * for the tax country as of the date the approval would post on (AW49). Empty when the bill is not approvable,
     * states no tax, or no regime has recovery on; null when recovery cannot be read now (unknown, ADR-0017).
     */
    public @Nullable List<VendorBillReview.TaxTypeChoice> choices(
            @NonNull VendorBill bill, @NonNull Optional<TaxProfileClient.TaxTypes> profile) {
        if (!APPROVABLE.contains(bill.getStatus()) || !statesTax(bill)) {
            return List.of();
        }
        String country = taxCountry.code();
        Set<String> enabledRegimes;
        try {
            LocalDate postingDate = postingService.postingDate(bill).date();
            enabledRegimes = recoveryFlags.regimes(postingDate).stream()
                    .filter(flag -> flag.enabled() && country.equals(flag.countryCode()))
                    .map(InputTaxRecoveryFlags.RegimeFlag::regime)
                    .collect(Collectors.toSet());
        } catch (TaxServiceUnavailableException e) {
            log.warn("Vendor-bill read without the tax-type choices: the tax service did not answer");
            return null;
        }
        if (enabledRegimes.isEmpty()) {
            return List.of();
        }
        if (profile.isEmpty()) {
            return null;
        }
        return profile.get().taxTypes().stream()
                .filter(type -> type.regime() != null && enabledRegimes.contains(type.regime()))
                .map(type -> new VendorBillReview.TaxTypeChoice(type.taxType(), type.label()))
                .toList();
    }

    private static boolean statesTax(VendorBill bill) {
        return bill.getTaxAmount() != null && bill.getTaxAmount().signum() != 0;
    }
}
