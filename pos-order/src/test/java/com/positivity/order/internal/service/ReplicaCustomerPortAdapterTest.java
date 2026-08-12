package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.CustomerLookupResult;
import com.positivity.order.internal.entity.ExtVehicle;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtVehicleRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ReplicaCustomerPortAdapter} (ADR-0044 domain wall, PR
 * #1089 resolution).
 *
 * <p>
 * The distinction this class exists to make is between <em>NOT_FOUND</em> and
 * <em>UNAVAILABLE</em>. A cold replica — the event feed disabled, or a table that
 * has never been populated — cannot tell the two apart, so it reports
 * UNAVAILABLE and the cart proceeds as VALIDATION_PENDING. Reporting NOT_FOUND
 * there would reject a perfectly valid customer for the accident of this module
 * not having caught up yet.
 *
 * <p>
 * Ownership is also enforced, not just existence: a vehicle that belongs to a
 * different account reads as NOT_FOUND for this customer, which is what stops a
 * clerk attaching someone else's vehicle to an order.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReplicaCustomerPortAdapter — cold-replica and ownership semantics")
class ReplicaCustomerPortAdapterTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000081");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000082");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000083");

    @Mock
    private ExtCustomerRepository extCustomerRepository;

    @Mock
    private ExtVehicleRepository extVehicleRepository;

    private ReplicaCustomerPortAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReplicaCustomerPortAdapter(extCustomerRepository, extVehicleRepository);
        eventFeed(true);
    }

    private void eventFeed(boolean enabled) {
        ReflectionTestUtils.setField(adapter, "eventFeedEnabled", enabled);
    }

    private static ExtVehicle vehicle(UUID accountId, boolean active) {
        ExtVehicle vehicle = new ExtVehicle();
        vehicle.setVehicleId(VEHICLE_ID);
        vehicle.setAccountId(accountId);
        vehicle.setActive(active);
        return vehicle;
    }

    @Test
    @DisplayName("reports UNAVAILABLE while the event feed is switched off")
    void feedDisabledIsUnavailable() {
        eventFeed(false);
        when(extCustomerRepository.count()).thenReturn(500L);

        assertThat(adapter.lookupCustomer(CUSTOMER_ID)).isEqualTo(CustomerLookupResult.UNAVAILABLE);
        assertThat(adapter.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).isEqualTo(CustomerLookupResult.UNAVAILABLE);
    }

    @Test
    @DisplayName("reports UNAVAILABLE on an empty replica rather than rejecting a valid customer")
    void emptyReplicaIsUnavailable() {
        when(extCustomerRepository.count()).thenReturn(0L);
        when(extVehicleRepository.count()).thenReturn(0L);

        assertThat(adapter.lookupCustomer(CUSTOMER_ID)).isEqualTo(CustomerLookupResult.UNAVAILABLE);
        assertThat(adapter.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).isEqualTo(CustomerLookupResult.UNAVAILABLE);
        // An empty table means "not caught up", so the row lookup is not even attempted.
        verify(extCustomerRepository, never()).existsById(any());
        verify(extVehicleRepository, never()).findById(any());
    }

    @Test
    @DisplayName("resolves a known customer to FOUND and an unknown one to NOT_FOUND")
    void customerLookup() {
        when(extCustomerRepository.count()).thenReturn(10L);
        when(extCustomerRepository.existsById(CUSTOMER_ID)).thenReturn(true);
        when(extCustomerRepository.existsById(OTHER_CUSTOMER_ID)).thenReturn(false);

        assertThat(adapter.lookupCustomer(CUSTOMER_ID)).isEqualTo(CustomerLookupResult.FOUND);
        assertThat(adapter.lookupCustomer(OTHER_CUSTOMER_ID)).isEqualTo(CustomerLookupResult.NOT_FOUND);
    }

    @Test
    @DisplayName("resolves a vehicle the customer owns to FOUND")
    void ownedVehicleIsFound() {
        when(extVehicleRepository.count()).thenReturn(10L);
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(CUSTOMER_ID, true)));

        assertThat(adapter.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).isEqualTo(CustomerLookupResult.FOUND);
    }

    @Test
    @DisplayName("refuses a vehicle owned by a different account")
    void vehicleOwnedByAnotherAccountIsNotFound() {
        when(extVehicleRepository.count()).thenReturn(10L);
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(OTHER_CUSTOMER_ID, true)));

        // Existence alone is not enough: this is what stops someone else's vehicle being attached.
        assertThat(adapter.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).isEqualTo(CustomerLookupResult.NOT_FOUND);
    }

    @Test
    @DisplayName("refuses an inactive vehicle and one with no replica row")
    void inactiveOrMissingVehicleIsNotFound() {
        when(extVehicleRepository.count()).thenReturn(10L);
        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(vehicle(CUSTOMER_ID, false)));

        assertThat(adapter.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).isEqualTo(CustomerLookupResult.NOT_FOUND);

        when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.empty());

        assertThat(adapter.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).isEqualTo(CustomerLookupResult.NOT_FOUND);
    }
}
