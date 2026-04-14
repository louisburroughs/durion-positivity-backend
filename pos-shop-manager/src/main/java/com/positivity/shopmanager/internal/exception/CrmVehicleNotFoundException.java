package com.positivity.shopmanager.internal.exception;

import java.util.UUID;

public class CrmVehicleNotFoundException extends RuntimeException {

    public CrmVehicleNotFoundException(UUID crmVehicleId) {
        super("CRM vehicle not found: " + crmVehicleId);
    }
}
