package com.positivity.customer.internal.entity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Customer interface extending PartyEntity (CAP:091 Story #104).
 * Customers are parties that can own and manage vehicles.
 */
public interface Customer extends PartyEntity {

    String getCustomerNumber();

    String getLastName();

    String getFirstName();

    String getPhoneNumber();

    String getEmail();

    // Inherited from PartyEntity:
    // UUID getId();
    // String getDisplayName();
    // String getStatus();
    // Set<String> getVehicleVins();
    // void addVehicleVin(String vin);
    // void removeVehicleVin(String vin);
    // String getPrimaryAddress();
}
