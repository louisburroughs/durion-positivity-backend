package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.entity.ExtSupplierVendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillTaxRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides what a vendor bill's posting does with the tax its document states (CAP:550 S32d item 10; C1 ruling
 * AW37-AW43, AW51, AW53): which typed amounts are recovered to {@code TAX_RECOVERABLE_<regime>} and which stay in their
 * line's class, as stated and never recalculated.
 *
 * <p><b>A tenant without recovery</b> (no regime's flag on at the bill date, every USD tenant today) gets {@link
 * Plan#NONE}: its bill books the gross exactly as before, and no pos-tax call is made.
 *
 * <p><b>A recovery-enabled tenant.</b> For each stated tax type {@code τ}, its regime and placeholder recoverability come
 * from pos-tax's profile of a country the tenant is registered in (S32a). {@code τ} is recovered when it is recoverable,
 * its regime's flag is on at the bill date ({@link InputTaxRecoveryFlags}) and the bill's evidence is in order;
 * otherwise it is withheld with its reason and goes into its line's class:
 *
 * <ul>
 *   <li>{@code NOT_RECOVERABLE}: the type is not recoverable, has no regime, or the profile does not declare it;
 *   <li>{@code NOT_REGISTERED}: its regime's flag is off at the bill date;
 *   <li>{@code SUPPLIER_REGISTRATION_MISSING} (AW53): the country's evidence rule for {@code VENDOR_BILL} applies at
 *       the bill's gross and the vendor copy holds no registration of the country's supplier regime. Only the
 *       vendor copy's {@code {scheme, region, last4}} is read, never the vendor's number;
 *   <li>{@code TAX_SPLIT_MISSING} (AW51): the bill states a tax total but no tax by type, or typed amounts that do not
 *       add up to it. Nothing is recovered and the bill is not held; automatic approval leaves it for a person.
 * </ul>
 *
 * <p>A profile or evidence rule that cannot be read throws {@link TaxServiceUnavailableException}: the approval
 * answers 503 and automatic approval skips the bill, so recovery is never read as "off" (AW49). No country, regime,
 * tax type or account is named here.
 */
@Component
@RequiredArgsConstructor
public class VendorBillTaxSplit {

    /** The prefix of the {@code VENDOR_BILL} mapping key a regime's recovered tax posts to. */
    public static final String RECOVERABLE_KEY_PREFIX = "TAX_RECOVERABLE_";

    /** The document type of a vendor bill in pos-tax's evidence rules. */
    static final String DOCUMENT_TYPE = "VENDOR_BILL";

    /** The evidence rule that asks for the supplier's registration. */
    static final String SUPPLIER_REGISTRATION_RULE = "SUPPLIER_REGISTRATION_NUMBER";

    private static final int SCALE = 2;

    private final InputTaxRecoveryFlags flags;
    private final TaxProfileClient taxProfiles;
    private final VendorBillTaxRepository billTaxes;
    private final ExtSupplierVendorRepository vendorCopies;

    /** Why a stated amount was not recovered. */
    public enum Withheld {
        /** Its regime's recovery flag is off at the bill date. */
        NOT_REGISTERED,
        /** The tax type is not recoverable, has no regime, or the profile does not declare it. */
        NOT_RECOVERABLE,
        /** The bill states no tax by type, or typed amounts that do not add up to its tax (AW51). */
        TAX_SPLIT_MISSING,
        /** The evidence rule applies and the vendor copy holds no supplier registration (AW53). */
        SUPPLIER_REGISTRATION_MISSING
    }

    /**
     * One stated tax type, as a positive amount.
     *
     * @param taxType the configured tax-type code
     * @param amount its amount, positive
     */
    public record Typed(@NonNull String taxType, @NonNull BigDecimal amount) {}

    /**
     * What pos-tax's profile says of one tax type.
     *
     * @param countryCode the country whose profile declares it
     * @param regime the regime it is recovered under; null for none
     * @param recoverable its placeholder recoverability (OI-4)
     */
    public record TaxTypeInfo(
            @NonNull String countryCode, @Nullable String regime, boolean recoverable) {}

    /**
     * What the posting does with one stated amount.
     *
     * @param taxType the tax type; null for the unsplit tax
     * @param regime its regime, when known
     * @param amount the stated amount, positive
     * @param mappingKey the {@code VENDOR_BILL} key it is recovered to; null when withheld
     * @param withheld why it is not recovered; null when it is
     */
    public record Item(
            @Nullable String taxType,
            @Nullable String regime,
            @NonNull BigDecimal amount,
            @Nullable String mappingKey,
            @Nullable Withheld withheld) {

        /** Whether the amount is recovered. */
        public boolean recovered() {
            return withheld == null;
        }
    }

    /**
     * The decision for one bill.
     *
     * @param enabled whether the tenant recovers input tax at the bill date; false keeps the bill's gross booking
     * @param items one per stated tax type, or one for the unsplit tax; empty when the bill states no tax
     */
    public record Plan(boolean enabled, @NonNull List<Item> items) {

        /** A tenant without recovery: the bill books the gross. */
        public static final Plan NONE = new Plan(false, List.of());

        /** The recovered amounts by mapping key, positive, in item order. */
        public @NonNull Map<String, BigDecimal> recoveredByKey() {
            Map<String, BigDecimal> recovered = new LinkedHashMap<>();
            for (Item item : items) {
                if (item.recovered()) {
                    recovered.merge(Objects.requireNonNull(item.mappingKey()), item.amount(), BigDecimal::add);
                }
            }
            return recovered;
        }

        /**
         * Why automatic approval must leave the bill for a person: its tax is not split (AW51) or its evidence is
         * missing (AW53). Empty otherwise.
         */
        public @NonNull Optional<Withheld> automaticApprovalHold() {
            return items.stream()
                    .map(Item::withheld)
                    .filter(w -> w == Withheld.TAX_SPLIT_MISSING || w == Withheld.SUPPLIER_REGISTRATION_MISSING)
                    .findFirst();
        }
    }

    /**
     * The decision for {@code bill}, from its stated tax, its stated tax by type and the bound tenant's flags at the
     * bill date.
     *
     * @throws TaxServiceUnavailableException when a profile or an evidence rule cannot be read
     */
    public @NonNull Plan plan(@NonNull VendorBill bill) {
        LocalDate date = bill.getBillDate().toLocalDate();
        List<String> countries = flags.regimes(date).stream()
                .filter(InputTaxRecoveryFlags.RegimeFlag::enabled)
                .map(InputTaxRecoveryFlags.RegimeFlag::countryCode)
                .distinct()
                .toList();
        if (countries.isEmpty()) {
            return Plan.NONE;
        }
        BigDecimal tax = positive(bill.getTaxAmount());
        List<Typed> typed = bill.getVendorBillId() == null
                ? List.of()
                : billTaxes.findByVendorBillIdOrderByTaxType(bill.getVendorBillId()).stream()
                        .map(t -> new Typed(t.getTaxType(), positive(t.getAmount())))
                        .toList();
        Map<String, TaxProfileClient.TaxTypes> profiles = new LinkedHashMap<>();
        Map<String, Boolean> evidenceMissing = new HashMap<>();
        return decide(
                tax,
                typed,
                type -> lookup(type, countries, profiles),
                regime -> flags.inputTaxRecovery(date, regime),
                country -> evidenceMissing.computeIfAbsent(country, c -> evidenceMissing(bill, c, date)));
    }

    /**
     * The decision itself, without I/O (tested directly).
     *
     * @param tax the bill's stated tax, positive
     * @param typed its stated tax by type, positive amounts
     * @param lookup what the profile says of a tax type; empty when no registered country declares it
     * @param regimeOn whether a regime's flag is on at the bill date
     * @param evidenceMissing whether a country's evidence rule applies and the vendor copy lacks the registration
     */
    static @NonNull Plan decide(
            @NonNull BigDecimal tax,
            @NonNull List<Typed> typed,
            @NonNull Function<String, Optional<TaxTypeInfo>> lookup,
            @NonNull Predicate<String> regimeOn,
            @NonNull Predicate<String> evidenceMissing) {
        if (tax.signum() == 0) {
            return new Plan(true, List.of());
        }
        BigDecimal typedTotal = typed.stream().map(Typed::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (typed.isEmpty() || typedTotal.compareTo(tax) != 0) {
            return new Plan(true, List.of(new Item(null, null, tax, null, Withheld.TAX_SPLIT_MISSING)));
        }
        List<Item> items = new ArrayList<>();
        for (Typed amount : typed) {
            if (amount.amount().signum() == 0) {
                continue;
            }
            Optional<TaxTypeInfo> info = lookup.apply(amount.taxType());
            String regime = info.map(TaxTypeInfo::regime).orElse(null);
            Withheld withheld;
            if (info.isEmpty() || regime == null || !info.get().recoverable()) {
                withheld = Withheld.NOT_RECOVERABLE;
            } else if (!regimeOn.test(regime)) {
                withheld = Withheld.NOT_REGISTERED;
            } else if (evidenceMissing.test(info.get().countryCode())) {
                withheld = Withheld.SUPPLIER_REGISTRATION_MISSING;
            } else {
                withheld = null;
            }
            items.add(new Item(
                    amount.taxType(),
                    regime,
                    amount.amount(),
                    withheld == null ? RECOVERABLE_KEY_PREFIX + regime : null,
                    withheld));
        }
        return new Plan(true, List.copyOf(items));
    }

    private Optional<TaxTypeInfo> lookup(
            String taxType, List<String> countries, Map<String, TaxProfileClient.TaxTypes> profiles) {
        for (String country : countries) {
            TaxProfileClient.TaxTypes profile = profiles.computeIfAbsent(country, taxProfiles::taxTypes);
            Optional<TaxProfileClient.TaxType> declared = profile.taxType(taxType);
            if (declared.isPresent()) {
                return Optional.of(new TaxTypeInfo(
                        country, declared.get().regime(), declared.get().inputTaxRecoverable()));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether {@code country}'s evidence rule for vendor bills applies at the bill's gross and the vendor copy holds no
     * registration of the country's supplier regime (AW53). A country that names a rule but no supplier regime cannot
     * be satisfied, so the evidence is missing: under-claiming is safe.
     */
    private boolean evidenceMissing(VendorBill bill, String country, LocalDate date) {
        TaxProfileClient.EvidenceRules rules = taxProfiles.evidenceRules(country, date);
        BigDecimal gross = positive(bill.getTotalAmount());
        boolean applies = rules.rules().stream()
                .filter(rule -> SUPPLIER_REGISTRATION_RULE.equals(rule.rule()))
                .filter(rule -> rule.appliesTo() != null && rule.appliesTo().contains(DOCUMENT_TYPE))
                .anyMatch(rule -> rule.fromAmount() != null && gross.compareTo(rule.fromAmount()) >= 0);
        if (!applies) {
            return false;
        }
        String regime = rules.supplierRegistrationRegime();
        if (regime == null) {
            return true;
        }
        return vendorCopies
                .findById(bill.getVendorId())
                .map(ExtSupplierVendor::getTaxRegistrations)
                .orElse(List.of())
                .stream()
                .noneMatch(registration -> regime.equals(registration.get("scheme")));
    }

    /** {@code amount} as a positive amount at the cent; null is zero. */
    static @NonNull BigDecimal positive(@Nullable BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(SCALE) : amount.abs().setScale(SCALE, RoundingMode.HALF_UP);
    }
}
