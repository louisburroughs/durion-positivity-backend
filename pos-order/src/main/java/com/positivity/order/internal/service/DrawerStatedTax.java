package com.positivity.order.internal.service;

import com.positivity.order.internal.client.TaxPlausibilityPort;
import com.positivity.order.internal.client.TaxPlausibilityPort.Checked;
import com.positivity.order.internal.client.TaxPlausibilityPort.Disagreement;
import com.positivity.order.internal.client.TaxPlausibilityPort.Implausible;
import com.positivity.order.internal.client.TaxPlausibilityPort.PlausibilityAnswer;
import com.positivity.order.internal.client.TaxPlausibilityPort.PlausibilityQuery;
import com.positivity.order.internal.client.TaxPlausibilityPort.StatedAmount;
import com.positivity.order.internal.dto.CashMovementStatedTax;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtLocation;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.exception.CashMovementTaxRefusedException;
import com.positivity.order.internal.exception.RegisterSessionRequestValidationException;
import com.positivity.order.internal.exception.TaxCheckUnavailableException;
import com.positivity.order.internal.repository.CashMovementStatedTaxRepository;
import com.positivity.order.internal.repository.ExtLocationRepository;
import com.positivity.order.internal.service.model.CashMovementOptions;
import com.positivity.shared.error.ApiError;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The drawer's stated tax on a petty expense (CAP:550 S32d items 5 and 6; Order re-confirmation 2026-10-08): which
 * regimes a session offers, pos-tax's plausibility check, and the stated amounts' rows.
 *
 * <p><b>Offered regimes (amendment A1).</b> A regime is offered only when all of these hold, from local replicas and
 * with no remote call: the session has a location; the category is {@code taxRecoverable} in pos-order's copy; the
 * regime's registration is in effect on the movement's date in the {@code ext_tax_registration} copy; its {@code
 * jurisdictionCode} is the location's country or its region; and the drawer's currency is the currency of the
 * registration's country, from the JDK's ISO 3166 → 4217 data (ADR-0067). Only the posting decides what is claimed.
 *
 * <p><b>The movement's date</b> is the calendar date of its instant in UTC: pos-order holds no copy of the accounting
 * calendar zone, and UTC is the zone provisioning seeds for every tenant. Near midnight in another zone this can only
 * under-claim; the posting stays authoritative.
 *
 * <p><b>pos-tax's answers</b> are matched on their {@code code}, never on the status alone: only 422 {@code
 * TAX_AMOUNT_IMPLAUSIBLE} is relayed; every other 4xx is a disagreement between pos-order's replicas and pos-tax,
 * logged at WARN with pos-tax's code and counted on {@value #DISAGREEMENT_COUNTER} with a bounded {@code code} tag.
 * A disagreement or an unavailable pos-tax is 503 {@code TAX_CHECK_UNAVAILABLE} when the supplier's number was sent,
 * and otherwise records the amounts as {@code RATE_UNAVAILABLE}. Neither the supplier's name nor the number ever
 * reaches a log, a message or a metric.
 */
@Slf4j
@Service
public class DrawerStatedTax {

    static final String DISAGREEMENT_COUNTER = "pos.order.tax_check.disagreement";
    static final String PLAUSIBLE = "PLAUSIBLE";
    static final String RATE_UNAVAILABLE = "RATE_UNAVAILABLE";
    static final String OTHER = "OTHER";

    /** pos-tax codes the disagreement tag may carry; any other code is {@value #OTHER}, so the tag set is closed. */
    static final Set<String> KNOWN_DISAGREEMENT_CODES = Set.of(
            "AMOUNT_PRECISION_EXCEEDS_CURRENCY",
            "CURRENCY_NOT_SUPPORTED",
            "TAX_JURISDICTION_NOT_CONFIGURED",
            "TAX_REGIME_NOT_DECLARED",
            "VALIDATION_ERROR");

    private final ExtLocationRepository locations;
    private final TaxRegistrationReplica registrations;
    private final CashMovementStatedTaxRepository statedTaxes;
    private final TaxPlausibilityPort taxPlausibility;
    private final @Nullable MeterRegistry meterRegistry;

    public DrawerStatedTax(
            ExtLocationRepository locations,
            TaxRegistrationReplica registrations,
            CashMovementStatedTaxRepository statedTaxes,
            TaxPlausibilityPort taxPlausibility,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.locations = locations;
        this.registrations = registrations;
        this.statedTaxes = statedTaxes;
        this.taxPlausibility = taxPlausibility;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** What a check recorded on the movement. */
    public record TaxCheck(
            @NonNull String taxPlausibility, @Nullable Boolean supplierRegistrationRequired) {}

    /** The movement's date (S32d section B): the instant's calendar date in UTC. */
    public static @NonNull LocalDate movementDate(@NonNull Instant occurredAt) {
        return LocalDate.ofInstant(occurredAt, ZoneOffset.UTC);
    }

    /**
     * The regimes the session offers for {@code category} on {@code date}, in regime order; empty for a USD drawer, a
     * category that is not recoverable, or a session without a location.
     */
    public @NonNull List<String> offeredRegimes(
            @NonNull RegisterSession session, @Nullable ExtAccountingPettyExpenseCategory category, LocalDate date) {
        if (category == null || !category.isActive() || !category.isTaxRecoverable()) {
            return List.of();
        }
        return registeredRegimes(session, date);
    }

    /**
     * The regimes registered for the session's location and currency on {@code date}, whatever the category. Empty
     * when the session has no location or its location has no country.
     */
    public @NonNull List<String> registeredRegimes(@NonNull RegisterSession session, @NonNull LocalDate date) {
        Optional<Place> place = place(session);
        if (place.isEmpty()) {
            return List.of();
        }
        String country = place.get().country();
        String region = place.get().region();
        return registrations.inEffectFor(country, date).stream()
                .filter(registration -> session.getCurrencyCode().equals(currencyOf(registration.countryCode())))
                .filter(registration -> covers(registration.jurisdictionCode(), country, region))
                .map(TaxRegistrationReplica.Registration::regime)
                .distinct()
                .toList();
    }

    /**
     * The drawer receipt's evidence rule for the session's country on {@code date}; null when no regime is
     * registered there, the session has no location, or pos-tax does not answer. Never throws because of pos-tax.
     */
    public CashMovementOptions.@Nullable EvidenceRule evidenceRule(
            @NonNull RegisterSession session, @NonNull LocalDate date) {
        Optional<Place> place = place(session);
        if (place.isEmpty()) {
            return null;
        }
        try {
            return taxPlausibility
                    .drawerEvidenceRule(place.get().country(), date)
                    .filter(rule -> rule.currencyCode().equals(session.getCurrencyCode())
                            && DrawerAmounts.representable(rule.threshold(), rule.currencyCode()))
                    .map(rule -> new CashMovementOptions.EvidenceRule(
                            DrawerAmounts.atExponent(rule.threshold(), rule.currencyCode()), rule.currencyCode()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn(
                    "Evidence rule unavailable for the drawer options: {}",
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Asks pos-tax about the stated amounts (step 4 of the binding call order): called only with a non-empty list,
     * outside the session's row lock and before an approval token is used.
     *
     * @param session            the drawer
     * @param date               the movement's date
     * @param total              the movement's amount, the receipt total
     * @param amounts            the stated amounts, already checked locally
     * @param registrationNumber the normalised number, when the register sent one
     * @return what to record
     * @throws RegisterSessionRequestValidationException 400 when pos-tax found the number malformed
     * @throws CashMovementTaxRefusedException           422 when pos-tax found an amount implausible, or a number was
     *                                                   sent for a country that names no supplier regime
     * @throws TaxCheckUnavailableException              503 when a number was sent and pos-tax is unavailable or
     *                                                   disagrees
     */
    public @NonNull TaxCheck check(
            @NonNull RegisterSession session,
            @NonNull LocalDate date,
            @NonNull BigDecimal total,
            @NonNull List<StatedAmount> amounts,
            @Nullable String registrationNumber) {
        Place place = place(session).orElse(null);
        PlausibilityAnswer answer = place == null
                ? new TaxPlausibilityPort.Unavailable()
                : taxPlausibility.check(new PlausibilityQuery(
                        place.country(),
                        place.region(),
                        place.postalCode(),
                        place.city(),
                        date,
                        session.getCurrencyCode(),
                        total,
                        amounts,
                        registrationNumber));
        boolean numberSent = registrationNumber != null;
        return switch (answer) {
            case Checked checked -> checked(checked, numberSent);
            case Implausible implausible ->
                throw new CashMovementTaxRefusedException(
                        CashMovementTaxRefusedException.Code.TAX_AMOUNT_IMPLAUSIBLE,
                        implausible.message(),
                        implausible.fieldErrors());
            case Disagreement disagreement -> {
                countDisagreement(disagreement);
                yield unavailable(numberSent);
            }
            case TaxPlausibilityPort.Unavailable _ -> unavailable(numberSent);
        };
    }

    private TaxCheck checked(Checked checked, boolean numberSent) {
        if (!PLAUSIBLE.equals(checked.outcome()) && !RATE_UNAVAILABLE.equals(checked.outcome())) {
            // An outcome this drawer does not know is not a plausibility answer it can record.
            countDisagreement(new Disagreement(200, null));
            return unavailable(numberSent);
        }
        if (numberSent) {
            if (checked.wellFormed() == null) {
                // Amendment A3: a closed field is never recorded unchecked (ADR-0072 Decision 1).
                throw new CashMovementTaxRefusedException(
                        CashMovementTaxRefusedException.Code.SUPPLIER_REGISTRATION_NOT_ACCEPTED,
                        "The supplier's registration number cannot be accepted for this location's country",
                        List.of(new ApiError.FieldError(
                                "supplierRegistrationNumber",
                                "this location's country names no supplier registration regime")));
            }
            if (!checked.wellFormed()) {
                throw new RegisterSessionRequestValidationException(
                        "supplierRegistrationNumber is not a well-formed registration number",
                        List.of(new ApiError.FieldError(
                                "supplierRegistrationNumber", "is not a well-formed registration number")));
            }
        }
        return new TaxCheck(checked.outcome(), checked.supplierRegistrationRequired());
    }

    private static TaxCheck unavailable(boolean numberSent) {
        if (numberSent) {
            throw new TaxCheckUnavailableException(
                    "The supplier's registration number cannot be checked right now; retry, or record the movement"
                            + " without the number");
        }
        return new TaxCheck(RATE_UNAVAILABLE, null);
    }

    private void countDisagreement(Disagreement disagreement) {
        String code = disagreement.code();
        log.warn(
                "pos-tax disagreed with the drawer's replicas on a stated-tax check: status={} code={}",
                disagreement.status(),
                code);
        if (meterRegistry != null) {
            Counter.builder(DISAGREEMENT_COUNTER)
                    .description("pos-tax answers that pos-order's own checks should have made impossible")
                    .tag("code", disagreementTag(disagreement))
                    .register(meterRegistry)
                    .increment();
        }
    }

    /** The bounded tag: pos-tax's code when it is a known one on a 400 or 422, else {@value #OTHER}. */
    static String disagreementTag(Disagreement disagreement) {
        String code = disagreement.code();
        boolean refusal = disagreement.status() == 400 || disagreement.status() == 422;
        return refusal && code != null && KNOWN_DISAGREEMENT_CODES.contains(code) ? code : OTHER;
    }

    /** Records the movement's stated amounts, at the drawer currency's exponent. */
    public void record(@NonNull UUID movementId, @NonNull List<StatedAmount> amounts) {
        for (StatedAmount amount : amounts) {
            statedTaxes.save(com.positivity.order.internal.entity.CashMovementStatedTax.builder()
                    .movementId(movementId)
                    .regime(amount.regime())
                    .amount(amount.amount())
                    .build());
        }
        if (!amounts.isEmpty()) {
            statedTaxes.flush();
        }
    }

    /**
     * The stated amounts of each movement, at {@code currencyCode}'s exponent and in regime order; a movement without
     * any maps to an empty list.
     */
    public @NonNull Map<UUID, List<CashMovementStatedTax>> statedTaxes(
            @NonNull Collection<UUID> movementIds, @NonNull String currencyCode) {
        Map<UUID, List<CashMovementStatedTax>> byMovement = new LinkedHashMap<>();
        if (movementIds.isEmpty()) {
            return byMovement;
        }
        for (com.positivity.order.internal.entity.CashMovementStatedTax row :
                statedTaxes.findByMovementIdInOrderByRegimeAsc(movementIds)) {
            byMovement
                    .computeIfAbsent(row.getMovementId(), _ -> new java.util.ArrayList<>())
                    .add(new CashMovementStatedTax(row.getRegime(), atExponent(row.getAmount(), currencyCode)));
        }
        return byMovement;
    }

    /** A stored amount at the currency's exponent; a row is always representable, so nothing is rounded. */
    private static BigDecimal atExponent(BigDecimal amount, String currencyCode) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return DrawerAmounts.representable(stripped, currencyCode)
                ? DrawerAmounts.atExponent(stripped, currencyCode)
                : stripped;
    }

    /** The session's place, from the {@code ext_location} copy of its stamped location (AW36). */
    private Optional<Place> place(RegisterSession session) {
        UUID locationId = session.getLocationId();
        if (locationId == null) {
            return Optional.empty();
        }
        return locations.findById(locationId).flatMap(DrawerStatedTax::place);
    }

    private static Optional<Place> place(ExtLocation location) {
        String country = upper(location.getCountry());
        if (country == null) {
            return Optional.empty();
        }
        String region = upper(location.getRegion());
        if (region != null && region.startsWith(country + "-")) {
            region = region.substring(country.length() + 1);
        }
        return Optional.of(new Place(country, region, location.getPostalCode(), location.getCity()));
    }

    private static boolean covers(String jurisdictionCode, String country, @Nullable String region) {
        String jurisdiction = upper(jurisdictionCode);
        return jurisdiction != null && (jurisdiction.equals(country) || jurisdiction.equals(region));
    }

    /** The currency of {@code countryCode} from the JDK's ISO 3166 → 4217 data; null when it has none. */
    static @Nullable String currencyOf(String countryCode) {
        try {
            Currency currency = Currency.getInstance(Locale.of("", countryCode));
            return currency == null ? null : currency.getCurrencyCode();
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static @Nullable String upper(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private record Place(
            @NonNull String country,
            @Nullable String region,
            @Nullable String postalCode,
            @Nullable String city) {}
}
