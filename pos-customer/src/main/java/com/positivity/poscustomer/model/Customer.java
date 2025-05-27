package com.positivity.poscustomer.model;

import lombok.Data;
import java.util.List;

public interface Customer {
    Long getId();
    String getCustomerNumber();
    String getLastName();
    String getFirstName();
    String getPhoneNumber();
    String getEmail();
    List<String> getVehicleVins();
}

