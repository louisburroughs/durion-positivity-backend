package com.positivity.shopmanager.internal.exception;

import java.util.UUID;

public class VehicleCustomerMismatchException extends RuntimeException {

    public VehicleCustomerMismatchException(UUID crmVehicleId, UUID crmCustomerId) {
        super("CRM vehicle " + crmVehicleId + " is not associated with customer " + crmCustomerId);
    }
}