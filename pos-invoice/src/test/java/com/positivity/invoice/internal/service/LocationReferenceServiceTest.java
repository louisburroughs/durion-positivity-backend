package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.entity.ExtLocationReplica;
import com.positivity.invoice.internal.repository.ExtLocationReplicaRepository;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.web.common.ReplicationPendingException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #1994: an absent {@code ext_location} row is "not yet", a row without an address stays a conflict. */
class LocationReferenceServiceTest {

    private static final UUID LOCATION_ID = UUID.fromString("01960003-0000-7000-8000-0000000000c1");

    private final ExtLocationReplicaRepository repository = mock(ExtLocationReplicaRepository.class);
    private final LocationReferenceService service = new LocationReferenceService(repository);

    @Test
    @DisplayName("a location with no replica row is 503 LOCATION_REPLICATION_PENDING naming the awaited id")
    void missingReplicaRowIsReplicationPending() {
        when(repository.findById(LOCATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveTaxAddress(LOCATION_ID))
                .isInstanceOfSatisfying(ReplicationPendingException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LOCATION_REPLICATION_PENDING");
                    assertThat(e.getReferenceId()).isEqualTo(LOCATION_ID);
                });
    }

    @Test
    @DisplayName("a replicated location without country/postal code stays an IllegalStateException (409)")
    void presentButIncompleteRowKeepsItsStatus() {
        ExtLocationReplica location = new ExtLocationReplica();
        location.setLocationId(LOCATION_ID);
        location.setCountry("US");
        when(repository.findById(LOCATION_ID)).thenReturn(Optional.of(location));

        assertThatThrownBy(() -> service.resolveTaxAddress(LOCATION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("country/postalCode");
    }

    @Test
    @DisplayName("a complete replica row maps to the tax destination address")
    void completeRowMapsToTaxAddress() {
        ExtLocationReplica location = new ExtLocationReplica();
        location.setLocationId(LOCATION_ID);
        location.setCountry(" US ");
        location.setPostalCode("30301");
        location.setCity("Atlanta");
        when(repository.findById(LOCATION_ID)).thenReturn(Optional.of(location));

        TaxAddress address = service.resolveTaxAddress(LOCATION_ID);

        assertThat(address.getCountryCode()).isEqualTo("US");
        assertThat(address.getPostalCode()).isEqualTo("30301");
        assertThat(address.getCity()).isEqualTo("Atlanta");
    }
}
