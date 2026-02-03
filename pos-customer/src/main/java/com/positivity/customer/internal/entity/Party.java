package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;

/**
 * High-level Party abstraction for vehicle associations (CAP:091 Story #104).
 * Both Customer and CommercialParty can be Party entities.
 * Vehicles belong to this Party interface, enabling polymorphic relationships.
 */
public interface Party {

    /**
     * Unique identifier for this party.
     */
    UUID getPartyId();

    /**
     * Display name for this party (customer name or organization name).
     */
    String getDisplayName();

    /**
     * Status of the party (ACTIVE, INACTIVE, SUSPENDED).
     */
    AccountStatus getStatus();

    /**
     * Vehicle VINs associated with this party.
     */
    Set<String> getVehicleVins();

    /**
     * Add vehicle VIN to this party.
     */
    void addVehicleVin(String vin);

    /**
     * Remove vehicle VIN from this party.
     */
    void removeVehicleVin(String vin);

    /**
     * Primary contact information for this party.
     */
    String getPrimaryAddress();

    /**
     * Party creation timestamp.
     */
    Instant getCreatedAt();

    /**
     * Party last modification timestamp.
     */
    Instant getModifiedAt();

    /**
     * Party type discriminator (CUSTOMER, COMMERCIAL, INDIVIDUAL, etc).
     * Implemented as default to provide sensible default behavior.
     */
    default PartyType getPartyType() {
        return PartyType.UNKNOWN;
    }

    /**
     * Set the status of the party.
     */
    void setStatus(AccountStatus status);

    /**
     * Set the vehicle VINs for this party.
     */
    void setVehicleVins(Set<String> vehicleVins);
}
