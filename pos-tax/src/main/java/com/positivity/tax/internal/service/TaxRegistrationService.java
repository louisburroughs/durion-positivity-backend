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
 * <p>Refusals, in this order (ADR-0017): a missing or malformed field is 400 {@code VALIDATION_ERROR} with field errors
 * that name the field and the rule, never a value; a country without a profile is 422 {@code
 * TAX_JURISDICTION_NOT_CONFIGURED} and a regime it does not declare is 422 {@code TAX_REGIME_NOT_DECLARED}; on a change,
 * an unknown id is 404; a number that does not match the regime's configured shape is 400 with {@code
 * fieldErrors[registrationNumber]}, and nothing is stored or queued. Then the request id: one already applied to the
 * same operation, registration and values returns the first result (as it stood after that write), and one used for
 * anything else is 409 {@code IDEMPOTENCY_CONFLICT}. Last, a stale version is 409 {@code OPTIMISTIC_LOCK} and an
 * overlap with another registration of the same country and regime is 409 {@code TAX_REGISTRATION_OVERLAP}.
 *
 * <p>A status is derived on today's UTC date from the dates and never stored; a reader acting as of a business date
 * uses the effective dates, not the status.
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
