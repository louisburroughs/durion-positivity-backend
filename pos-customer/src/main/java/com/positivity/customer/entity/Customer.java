package com.positivity.customer.entity;

import java.util.List;

public interface Customer {
    Long getId();

    String getCustomerNumber();

    String getLastName();

    String getFirstName();

    String getPhoneNumber();

    String getEmail();

    String getPrimaryAddress();

    List<String> getVehicleVins();
}
