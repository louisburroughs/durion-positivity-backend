package com.positivity.workorder.internal.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Minimal projection of pos-location's {@code LocationResponseDTO}, limited to the
 * address fields needed to build a tax jurisdiction address. Unknown properties are
 * ignored so this stays decoupled from the full location contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocationAddressResponse {

    private @Nullable String addressLine1;
    private @Nullable String addressLine2;
    private @Nullable String city;
    private @Nullable String state;
    private @Nullable String postalCode;
    private @Nullable String country;

    public @Nullable String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(@Nullable String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public @Nullable String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(@Nullable String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public @Nullable String getCity() {
        return city;
    }

    public void setCity(@Nullable String city) {
        this.city = city;
    }

    public @Nullable String getState() {
        return state;
    }

    public void setState(@Nullable String state) {
        this.state = state;
    }

    public @Nullable String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(@Nullable String postalCode) {
        this.postalCode = postalCode;
    }

    public @Nullable String getCountry() {
        return country;
    }

    public void setCountry(@Nullable String country) {
        this.country = country;
    }
}
