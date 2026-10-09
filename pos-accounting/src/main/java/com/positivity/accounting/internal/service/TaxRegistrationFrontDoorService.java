package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ChangeTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.RecordTaxRegistrationRequest;
import com.positivity.accounting.internal.dto.TaxRegistrationListResponse;
import com.positivity.accounting.internal.dto.TaxRegistrationView;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The accounting front door to the tenant's tax registrations (CAP:550 S32c; ADR-0071 §5, AW59). Reads come from
 * accounting's copy, {@code ext_tax_registration}; writes are checked here (justification, request id) and passed to
 * pos-tax, the registry's owner, with the person's id as the actor. pos-tax is never reached from a screen.
 */
public interface TaxRegistrationFrontDoorService {

    /**
     * The tenant's registrations from accounting's copy.
     *
     * @param asOf when given, only those in effect on that date (both ends inclusive)
     * @return the registrations
     */
    @NonNull
    TaxRegistrationListResponse list(@Nullable LocalDate asOf);

    /**
     * Records a registration through pos-tax.
     *
     * @param request the registration
     * @param actor   the person's user id, as the gateway forwarded it
     * @return pos-tax's answer, {@code replayed} when the request id was already applied
     */
    @NonNull
    Result create(@NonNull RecordTaxRegistrationRequest request, @NonNull UUID actor);

    /**
     * Changes a registration through pos-tax.
     *
     * @param registrationId the registration
     * @param request        the change
     * @param actor          the person's user id, as the gateway forwarded it
     * @return pos-tax's answer
     */
    @NonNull
    TaxRegistrationView update(
            @NonNull UUID registrationId, @NonNull ChangeTaxRegistrationRequest request, @NonNull UUID actor);

    /**
     * A create's outcome.
     *
     * @param registration the registration as pos-tax holds it
     * @param replayed     whether the request id had already been applied
     */
    record Result(@NonNull TaxRegistrationView registration, boolean replayed) {}
}
