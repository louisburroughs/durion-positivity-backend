package com.positivity.tax.internal.service;

import com.positivity.tax.internal.dto.TaxRegistrationCreateRequest;
import com.positivity.tax.internal.dto.TaxRegistrationResponse;
import com.positivity.tax.internal.dto.TaxRegistrationUpdateRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * The tenant tax-registration registry (CAP:550 S32c; ADR-0071 §7): pos-tax is its source of truth, written only
 * through the pos-accounting front door. Each committed change writes {@code tax.registration.changed} to the
 * outbox in the same transaction.
 *
 * <p>Refusals, in this order: a missing or malformed field is 400 {@code VALIDATION_ERROR} with field errors that
 * name the field and the rule, never a value; a request id already applied returns its first result; a country
 * without a profile or a regime it does not declare is 400; a number that does not match the regime's configured
 * shape is 400 with {@code fieldErrors[registrationNumber]}, and nothing is stored or queued; an unknown id is 404;
 * a stale version is 409 {@code OPTIMISTIC_LOCK}; an overlap with another registration of the same country and
 * regime is 409 {@code TAX_REGISTRATION_OVERLAP}.
 */
public interface TaxRegistrationService {

    /**
     * Records a new registration.
     *
     * @param request the registration
     * @return the registration, {@code replayed} when the request id was already applied
     */
    @NonNull
    WriteResult create(@NonNull TaxRegistrationCreateRequest request);

    /**
     * Changes a registration's number or dates.
     *
     * @param registrationId the registration
     * @param request        the change
     * @return the registration, {@code replayed} when the request id was already applied
     */
    @NonNull
    WriteResult update(@NonNull UUID registrationId, @NonNull TaxRegistrationUpdateRequest request);

    /**
     * A write's outcome.
     *
     * @param registration the registration as it stands
     * @param replayed     whether the request id had already been applied, so nothing was written
     */
    record WriteResult(@NonNull TaxRegistrationResponse registration, boolean replayed) {}
}
