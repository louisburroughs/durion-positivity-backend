package com.positivity.customer.internal.entity;

import java.util.List;
import java.util.UUID;

public interface Customer {
    UUID getId();

    String getCustomerNumber();

    String getLastName();

    String getFirstName();

    String getPhoneNumber();

    String getEmail();

    String getPrimaryAddress();

    List<String> getVehicleVins();
}
