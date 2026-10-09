package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.TaxRegistrationClient;
import com.positivity.accounting.internal.dto.ChangeTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.RecordTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.TaxRegistrationListResponse;
import com.positivity.accounting.internal.dto.TaxRegistrationView;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.repository.ExtTaxRegistrationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TaxRegistrationFrontDoorService} (CAP:550 S32c). The writes hold no transaction and store nothing here:
 * pos-tax owns the registry, and the copy follows from {@code tax.registration.changed}. A status is derived from the
 * dates on the reference date ({@code asOf}, else today in UTC); it is never stored.
 */
@Service
@RequiredArgsConstructor
public class TaxRegistrationFrontDoorServiceImpl implements TaxRegistrationFrontDoorService {

    private final ExtTaxRegistrationRepository registrations;
    private final TaxRegistrationClient client;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull TaxRegistrationListResponse list(@Nullable LocalDate asOf) {
        LocalDate reference = asOf != null ? asOf : LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        List<ExtTaxRegistration> rows = asOf == null
                ? registrations.findAllByOrderByCountryCodeAscRegimeAscEffectiveFromAsc()
                : registrations.findInEffectOn(asOf);
        return new TaxRegistrationListResponse(
                asOf, rows.stream().map(row -> view(row, reference)).toList());
    }

    @Override
    public @NonNull Result create(@NonNull RecordTaxRegistrationRequest request, @NonNull UUID actor) {
        request.requireValid();
        TaxRegistrationClient.Written written = client.create(request, actor.toString());
        return new Result(view(written.registration()), written.replayed());
    }

    @Override
    public @NonNull TaxRegistrationView update(
            @NonNull UUID registrationId, @NonNull ChangeTaxRegistrationRequest request, @NonNull UUID actor) {
        request.requireValid();
        return view(client.update(registrationId, request, actor.toString()).registration());
    }

    private static TaxRegistrationView view(ExtTaxRegistration row, LocalDate reference) {
        return new TaxRegistrationView(
                row.getRegistrationId(),
                row.getCountryCode(),
                row.getRegime(),
                row.getRegistrationNumber(),
                row.getJurisdictionCode(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                status(row.getEffectiveFrom(), row.getEffectiveTo(), reference),
                row.getAggregateVersion(),
                row.getChangedAt());
    }

    private TaxRegistrationView view(TaxRegistrationClient.Registration registration) {
        return new TaxRegistrationView(
                registration.registrationId(),
                registration.countryCode(),
                registration.regime(),
                registration.registrationNumber(),
                registration.jurisdictionCode(),
                registration.effectiveFrom(),
                registration.effectiveTo(),
                registration.status(),
                registration.version(),
                registration.updatedAt() != null ? registration.updatedAt() : Instant.now(clock));
    }

    /** SCHEDULED before the start, ENDED after the inclusive end, ACTIVE otherwise. */
    static String status(LocalDate effectiveFrom, @Nullable LocalDate effectiveTo, LocalDate reference) {
        if (reference.isBefore(effectiveFrom)) {
            return "SCHEDULED";
        }
        if (effectiveTo != null && reference.isAfter(effectiveTo)) {
            return "ENDED";
        }
        return "ACTIVE";
    }
}
