package com.positivity.shopmanager.internal.exception;

import java.util.UUID;

public class CrmCustomerNotFoundException extends RuntimeException {

    public CrmCustomerNotFoundException(UUID crmCustomerId) {
        super("CRM customer not found: " + crmCustomerId);
    }
}