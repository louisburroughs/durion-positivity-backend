package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.ExtTaxRegistration;
import com.positivity.order.internal.repository.ExtTaxRegistrationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TaxRegistrationReplica} over {@code ext_tax_registration} (CAP:550 S32c). */
@Service
@RequiredArgsConstructor
public class TaxRegistrationReplicaImpl implements TaxRegistrationReplica {

    private final ExtTaxRegistrationRepository registrations;

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<Registration> inEffectOn(
            @NonNull String countryCode, @NonNull String regime, @NonNull LocalDate businessDate) {
        return registrations.findInEffectOn(countryCode, regime, businessDate).stream()
                .findFirst()
                .map(TaxRegistrationReplicaImpl::registration);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<Registration> inEffectFor(@NonNull String countryCode, @NonNull LocalDate businessDate) {
        return registrations.findAllInEffectOn(countryCode, businessDate).stream()
                .map(TaxRegistrationReplicaImpl::registration)
                .toList();
    }

    private static Registration registration(ExtTaxRegistration row) {
        return new Registration(
                row.getRegistrationId(),
                row.getCountryCode(),
                row.getRegime(),
                row.getJurisdictionCode(),
                row.getEffectiveFrom(),
                row.getEffectiveTo());
    }
}
