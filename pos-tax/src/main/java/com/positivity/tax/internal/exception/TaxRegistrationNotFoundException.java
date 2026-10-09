package com.positivity.tax.internal.exception;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** No registration with this id for the bound tenant (CAP:550 S32c): 404 {@value #CODE}. */
public class TaxRegistrationNotFoundException extends RuntimeException {

    public static final String CODE = "TAX_REGISTRATION_NOT_FOUND";

    public TaxRegistrationNotFoundException(@NonNull UUID registrationId) {
        super("Tax registration " + registrationId + " not found");
    }
}
