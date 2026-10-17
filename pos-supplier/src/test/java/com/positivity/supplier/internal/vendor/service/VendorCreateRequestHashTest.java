package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.vendor.service.model.RemitToDto;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationDto;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The canonical form tells payloads apart (#2678 review): no re-tokenising, null is not empty, every input counts. */
@DisplayName("VendorCreateRequestHash (#2678)")
class VendorCreateRequestHashTest {

    private static VendorCreateRequest request(
            String legalName, String displayName, List<TaxRegistrationDto> registrations, RemitToDto remitTo) {
        return new VendorCreateRequest(null, legalName, displayName, registrations, remitTo, "NET30", "USD");
    }

    @Test
    @DisplayName("the same payload hashes the same")
    void stable() {
        assertThat(VendorCreateRequestHash.of(request("ACME", "Acme", List.of(), null)))
                .isEqualTo(VendorCreateRequestHash.of(request("ACME", "Acme", List.of(), null)))
                .hasSize(64);
    }

    @Test
    @DisplayName("a separator moving between adjacent fields does not collide")
    void adjacentFieldsDoNotReTokenise() {
        assertThat(VendorCreateRequestHash.of(request("ACME|", "Corp", List.of(), null)))
                .isNotEqualTo(VendorCreateRequestHash.of(request("ACME", "|Corp", List.of(), null)));
    }

    @Test
    @DisplayName("null and empty are different values")
    void nullIsNotEmpty() {
        RemitToDto withNull = new RemitToDto("P", "L1", null, "City", "ST", "00000", "US", null);
        RemitToDto withEmpty = new RemitToDto("P", "L1", "", "City", "ST", "00000", "US", null);
        assertThat(VendorCreateRequestHash.of(request("ACME", "Acme", List.of(), withNull)))
                .isNotEqualTo(VendorCreateRequestHash.of(request("ACME", "Acme", List.of(), withEmpty)));
    }

    @Test
    @DisplayName("a registration's id and number both count")
    void registrationsCount() {
        UUID id = UUID.randomUUID();
        String plain = VendorCreateRequestHash.of(
                request("ACME", "Acme", List.of(new TaxRegistrationDto(null, "EIN", "000-00-1234", null)), null));
        assertThat(VendorCreateRequestHash.of(
                        request("ACME", "Acme", List.of(new TaxRegistrationDto(id, "EIN", "000-00-1234", null)), null)))
                .isNotEqualTo(plain);
        assertThat(VendorCreateRequestHash.of(
                        request("ACME", "Acme", List.of(new TaxRegistrationDto(null, "EIN", "000-00-4321", null)), null)))
                .isNotEqualTo(plain);
    }
}
