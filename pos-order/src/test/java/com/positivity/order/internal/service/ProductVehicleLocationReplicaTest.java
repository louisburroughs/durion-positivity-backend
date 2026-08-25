package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.order.internal.entity.ExtLocation;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.ExtVehicle;
import com.positivity.order.internal.repository.ExtLocationRepository;
import com.positivity.order.internal.repository.ExtProductCodeRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ExtProductUomReplicaRepository;
import com.positivity.order.internal.repository.ExtVehicleRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

/**
 * Field mapping for the three single-entity replica listeners — product (parity
 * story B1), vehicle (story I2), and location (story B3).
 *
 * <p>
 * Each exists so pricing, ownership validation, and tax-jurisdiction resolution
 * can read local rows instead of making a synchronous REST call per order line.
 * The delivery contract they share is covered once in
 * {@link ReplicaListenerContractTest}; what is left here is the mapping itself
 * and the aggregate-version guard that stops a late snapshot overwriting a newer
 * one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Product, vehicle, and location replica mapping")
class ProductVehicleLocationReplicaTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtProductRepository extProductRepository;

    @Mock
    private ExtProductUomReplicaRepository extProductUomReplicaRepository;

    @Mock
    private ExtProductCodeRepository extProductCodeRepository;

    @Mock
    private ExtVehicleRepository extVehicleRepository;

    @Mock
    private ExtLocationRepository extLocationRepository;

    @BeforeEach
    void setUp() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(extProductRepository.findById(any())).thenReturn(Optional.empty());
        when(extVehicleRepository.findById(any())).thenReturn(Optional.empty());
        when(extLocationRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("ProductEventsListener")
    class Products {

        private ProductEventsListener listener() {
            return new ProductEventsListener(
                    clock,
                    objectMapper,
                    processedEventRepository,
                    extProductRepository,
                    extProductUomReplicaRepository,
                    extProductCodeRepository);
        }

        private String envelope(long version, String active) {
            return """
                    {"eventId":"evt-1","eventType":"%s","aggregateVersion":%d,
                     "payload":{"productId":"%s","sku":"BRK-100","name":"Brake pad",
                       "active":%s,"trackingLevel":"SERIAL"}}
                    """.formatted(ProductUpdatedV1.EVENT_TYPE, version, PRODUCT_ID, active);
        }

        @Test
        @DisplayName("replicates the SKU, name, and tracking level pricing needs")
        void replicatesProduct() {
            listener().onCatalogEvent(envelope(2, "true"));

            ArgumentCaptor<ExtProduct> captor = ArgumentCaptor.forClass(ExtProduct.class);
            verify(extProductRepository).save(captor.capture());
            ExtProduct saved = captor.getValue();
            assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(saved.getSku()).isEqualTo("BRK-100");
            assertThat(saved.getName()).isEqualTo("Brake pad");
            assertThat(saved.isActive()).isTrue();
            assertThat(saved.getTrackingLevel()).isEqualTo("SERIAL");
            assertThat(saved.getAggregateVersion()).isEqualTo(2);
            assertThat(saved.getSyncedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("carries a deactivation through rather than defaulting it to active")
        void inactiveProductIsReplicatedAsInactive() {
            listener().onCatalogEvent(envelope(2, "false"));

            ArgumentCaptor<ExtProduct> captor = ArgumentCaptor.forClass(ExtProduct.class);
            verify(extProductRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isFalse();
        }

        @Test
        @DisplayName("ignores a snapshot strictly older than the replica it already holds")
        void staleSnapshotIsIgnored() {
            ExtProduct existing = new ExtProduct();
            existing.setProductId(PRODUCT_ID);
            existing.setAggregateVersion(9);
            when(extProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

            listener().onCatalogEvent(envelope(2, "true"));

            verify(extProductRepository, never()).save(any());
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("applies a snapshot at the same version as the replica it already holds (#1486)")
        void snapshotAtTheSameVersionIsApplied() {
            // Load-bearing: pos-catalog's aggregateVersion strictly advances, so an equal version
            // means identical content, and POST .../facts/replay deliberately resends a fact at the
            // held version to repair a replica with wrong or missing rows. Skipping on equal would
            // silently turn replay into a no-op (#1486) — the old `>=` guard did exactly that.
            ExtProduct existing = new ExtProduct();
            existing.setProductId(PRODUCT_ID);
            existing.setAggregateVersion(2);
            when(extProductRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(existing));

            listener().onCatalogEvent(envelope(2, "true"));

            ArgumentCaptor<ExtProduct> captor = ArgumentCaptor.forClass(ExtProduct.class);
            verify(extProductRepository).save(captor.capture());
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("VehicleEventsListener")
    class Vehicles {

        private VehicleEventsListener listener() {
            return new VehicleEventsListener(clock, objectMapper, processedEventRepository, extVehicleRepository);
        }

        private String envelope(long version) {
            return """
                    {"eventId":"evt-1","eventType":"%s","aggregateVersion":%d,
                     "payload":{"vehicleId":"%s","accountId":"%s","active":true}}
                    """.formatted(VehicleUpdatedV1.EVENT_TYPE, version, VEHICLE_ID, ACCOUNT_ID);
        }

        @Test
        @DisplayName("replicates the owning account, which is what ownership validation reads")
        void replicatesVehicleOwnership() {
            listener().onVehicleEvent(envelope(1));

            ArgumentCaptor<ExtVehicle> captor = ArgumentCaptor.forClass(ExtVehicle.class);
            verify(extVehicleRepository).save(captor.capture());
            assertThat(captor.getValue().getVehicleId()).isEqualTo(VEHICLE_ID);
            assertThat(captor.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(captor.getValue().isActive()).isTrue();
            assertThat(captor.getValue().getSyncedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("ignores a snapshot strictly older than the replica it already holds")
        void staleSnapshotIsIgnored() {
            ExtVehicle existing = new ExtVehicle();
            existing.setVehicleId(VEHICLE_ID);
            existing.setAggregateVersion(3);
            when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(existing));

            listener().onVehicleEvent(envelope(1));

            verify(extVehicleRepository, never()).save(any());
        }

        @Test
        @DisplayName("applies a snapshot at the same version as the replica it already holds (#1486)")
        void snapshotAtTheSameVersionIsApplied() {
            // Load-bearing: pos-vehicle-inventory's publisher flushes a JPA @Version before emit,
            // so aggregateVersion strictly advances and an equal version means identical content.
            // POST .../facts/replay deliberately resends a fact at the held version to repair a
            // replica with wrong or missing rows; skipping on equal (the old `>=` guard) silently
            // turned replay into a no-op (#1486).
            ExtVehicle existing = new ExtVehicle();
            existing.setVehicleId(VEHICLE_ID);
            existing.setAggregateVersion(3);
            when(extVehicleRepository.findById(VEHICLE_ID)).thenReturn(Optional.of(existing));

            listener().onVehicleEvent(envelope(3));

            ArgumentCaptor<ExtVehicle> captor = ArgumentCaptor.forClass(ExtVehicle.class);
            verify(extVehicleRepository).save(captor.capture());
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("LocationEventsListener")
    class Locations {

        private LocationEventsListener listener() {
            return new LocationEventsListener(clock, objectMapper, processedEventRepository, extLocationRepository);
        }

        private String envelope(long version) {
            return """
                    {"eventId":"evt-1","eventType":"%s","aggregateVersion":%d,
                     "payload":{"locationId":"%s","code":"SHOP-1","name":"Main Shop","active":true,
                       "addressLine1":"1 Main St","addressLine2":null,"city":"Austin",
                       "region":"TX","postalCode":"78701","country":"US"}}
                    """.formatted(LocationUpdatedV1.EVENT_TYPE, version, LOCATION_ID);
        }

        @Test
        @DisplayName("replicates the full address that tax jurisdiction resolution depends on")
        void replicatesAddress() {
            listener().onLocationEvent(envelope(1));

            ArgumentCaptor<ExtLocation> captor = ArgumentCaptor.forClass(ExtLocation.class);
            verify(extLocationRepository).save(captor.capture());
            ExtLocation saved = captor.getValue();
            assertThat(saved.getCode()).isEqualTo("SHOP-1");
            assertThat(saved.getAddressLine1()).isEqualTo("1 Main St");
            assertThat(saved.getAddressLine2()).isNull();
            assertThat(saved.getCity()).isEqualTo("Austin");
            assertThat(saved.getRegion()).isEqualTo("TX");
            assertThat(saved.getPostalCode()).isEqualTo("78701");
            assertThat(saved.getCountry()).isEqualTo("US");
        }

        @Test
        @DisplayName("removes the replica when the location is deleted upstream")
        void deleteRemovesReplica() {
            listener().onLocationEvent("""
                            {"eventId":"evt-2","eventType":"%s","payload":{"locationId":"%s"}}""".formatted(LocationDeletedV1.EVENT_TYPE, LOCATION_ID));

            verify(extLocationRepository).deleteById(LOCATION_ID);
            verify(extLocationRepository, never()).save(any());
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("ignores a snapshot no newer than the replica it already holds")
        void staleSnapshotIsIgnored() {
            ExtLocation existing = new ExtLocation();
            existing.setLocationId(LOCATION_ID);
            existing.setAggregateVersion(6);
            when(extLocationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(existing));

            listener().onLocationEvent(envelope(5));

            verify(extLocationRepository, never()).save(any());
        }
    }
}
