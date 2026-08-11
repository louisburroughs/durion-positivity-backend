package com.positivity.vehicle.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.vehicle.internal.config.CarePreferenceEventPublisher;
import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.entity.VehicleCarePreference;
import com.positivity.vehicle.internal.entity.VehicleRecord;
import com.positivity.vehicle.internal.repository.VehicleCarePreferenceRepository;
import com.positivity.vehicle.internal.repository.VehicleRecordRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link VehiclePreferencesServiceImpl} — structured service interval and fact
 * emission (#1175).
 */
class VehiclePreferencesServiceImplTest {

    private static final UUID VEHICLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final VehicleCarePreferenceRepository preferencesRepository = mock(VehicleCarePreferenceRepository.class);
    private final VehicleRecordRepository vehicleRepository = mock(VehicleRecordRepository.class);
    private final CarePreferenceEventPublisher publisher = mock(CarePreferenceEventPublisher.class);

    private VehiclePreferencesServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VehiclePreferencesServiceImpl(preferencesRepository, vehicleRepository, publisher);
        when(preferencesRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private UpsertPreferencesRequest request(Map<String, Object> preferences, Integer serviceIntervalMonths) {
        return new UpsertPreferencesRequest(VEHICLE_ID, preferences, serviceIntervalMonths, null, USER_ID, USER_ID);
    }

    private VehicleRecord vehicleRecord() {
        return VehicleRecord.builder()
                .vehicleId(VEHICLE_ID)
                .accountId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .vin("1HGBH41JXMN109186")
                .vinNormalized("1HGBH41JXMN109186")
                .unitNumber("UNIT-1")
                .description("2022 Honda Accord")
                .build();
    }

    private VehicleCarePreference existing(Integer serviceIntervalMonths) {
        return VehicleCarePreference.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000010"))
                .vehicle(vehicleRecord())
                .preferences(new HashMap<>(Map.of("oilType", "synthetic")))
                .serviceIntervalMonths(serviceIntervalMonths)
                .createdByUserId(USER_ID)
                .updatedByUserId(USER_ID)
                .build();
    }

    @Test
    @DisplayName("Upsert stores the structured interval and emits the care-preference fact")
    void upsertStoresIntervalAndEmits() {
        when(vehicleRepository.existsById(VEHICLE_ID)).thenReturn(true);
        when(vehicleRepository.getReferenceById(VEHICLE_ID)).thenReturn(vehicleRecord());
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.empty());

        VehicleCarePreference saved = service.upsertPreferences(request(new HashMap<>(), 4));

        assertThat(saved.getServiceIntervalMonths()).isEqualTo(4);
        verify(publisher).publishCarePreferenceUpserted(saved);
    }

    @Test
    @DisplayName("Upsert with a null interval clears the per-vehicle override (PUT is a full replace)")
    void upsertNullIntervalClears() {
        when(vehicleRepository.existsById(VEHICLE_ID)).thenReturn(true);
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.of(existing(4)));

        VehicleCarePreference saved = service.upsertPreferences(request(new HashMap<>(), null));

        assertThat(saved.getServiceIntervalMonths()).isNull();
        verify(publisher).publishCarePreferenceUpserted(saved);
    }

    @Test
    @DisplayName("Legacy serviceIntervalMonths blob key is promoted into the structured field, not stored")
    void upsertPromotesLegacyBlobKey() {
        when(vehicleRepository.existsById(VEHICLE_ID)).thenReturn(true);
        when(vehicleRepository.getReferenceById(VEHICLE_ID)).thenReturn(vehicleRecord());
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.empty());
        Map<String, Object> blob = new HashMap<>(Map.of("serviceIntervalMonths", 9, "oilType", "synthetic"));

        VehicleCarePreference saved = service.upsertPreferences(request(blob, null));

        assertThat(saved.getServiceIntervalMonths()).isEqualTo(9);
        assertThat(saved.getPreferences()).doesNotContainKey("serviceIntervalMonths");
        assertThat(saved.getPreferences()).containsEntry("oilType", "synthetic");
    }

    @Test
    @DisplayName("An explicit structured interval wins over the legacy blob key")
    void explicitIntervalWinsOverLegacyKey() {
        when(vehicleRepository.existsById(VEHICLE_ID)).thenReturn(true);
        when(vehicleRepository.getReferenceById(VEHICLE_ID)).thenReturn(vehicleRecord());
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.empty());
        Map<String, Object> blob = new HashMap<>(Map.of("serviceIntervalMonths", 9));

        VehicleCarePreference saved = service.upsertPreferences(request(blob, 3));

        assertThat(saved.getServiceIntervalMonths()).isEqualTo(3);
        assertThat(saved.getPreferences()).doesNotContainKey("serviceIntervalMonths");
    }

    @Test
    @DisplayName("A non-numeric or out-of-range legacy blob value is rejected, not silently dropped")
    void rejectsInvalidLegacyBlobValue() {
        when(vehicleRepository.existsById(VEHICLE_ID)).thenReturn(true);
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertPreferences(
                        request(new HashMap<>(Map.of("serviceIntervalMonths", "soon")), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        service.upsertPreferences(request(new HashMap<>(Map.of("serviceIntervalMonths", 0)), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        service.upsertPreferences(request(new HashMap<>(Map.of("serviceIntervalMonths", 121)), null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(publisher, never()).publishCarePreferenceUpserted(any());
    }

    @Test
    @DisplayName("Merge with a null interval leaves the stored interval unchanged")
    void mergeNullIntervalLeavesUnchanged() {
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.of(existing(4)));

        VehicleCarePreference saved =
                service.mergePreferences(VEHICLE_ID, new HashMap<>(Map.of("washPreference", "hand")), null, USER_ID);

        assertThat(saved.getServiceIntervalMonths()).isEqualTo(4);
        assertThat(saved.getPreferences()).containsEntry("washPreference", "hand");
        verify(publisher).publishCarePreferenceUpserted(saved);
    }

    @Test
    @DisplayName("Merge updates the interval from the structured field or the legacy blob key")
    void mergeUpdatesInterval() {
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.of(existing(4)));

        VehicleCarePreference saved = service.mergePreferences(VEHICLE_ID, new HashMap<>(), 7, USER_ID);
        assertThat(saved.getServiceIntervalMonths()).isEqualTo(7);

        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.of(existing(4)));
        VehicleCarePreference viaLegacy =
                service.mergePreferences(VEHICLE_ID, new HashMap<>(Map.of("serviceIntervalMonths", 12)), null, USER_ID);
        assertThat(viaLegacy.getServiceIntervalMonths()).isEqualTo(12);
        assertThat(viaLegacy.getPreferences()).doesNotContainKey("serviceIntervalMonths");
    }

    @Test
    @DisplayName("Delete emits the tombstone fact; a delete of nothing emits nothing")
    void deleteEmitsTombstone() {
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.of(existing(4)));
        service.deletePreferences(VEHICLE_ID);
        verify(publisher).publishCarePreferenceDeleted(VEHICLE_ID);

        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.empty());
        service.deletePreferences(VEHICLE_ID);
        // still exactly one emission from the first call
        verify(publisher).publishCarePreferenceDeleted(VEHICLE_ID);
    }

    @Test
    @DisplayName("Emission happens via the publisher only after a successful save")
    void emitsAfterSave() {
        when(vehicleRepository.existsById(VEHICLE_ID)).thenReturn(true);
        when(vehicleRepository.getReferenceById(VEHICLE_ID)).thenReturn(vehicleRecord());
        when(preferencesRepository.findByVehicle_VehicleId(VEHICLE_ID)).thenReturn(Optional.empty());

        service.upsertPreferences(request(new HashMap<>(), null));

        ArgumentCaptor<VehicleCarePreference> captor = ArgumentCaptor.forClass(VehicleCarePreference.class);
        verify(preferencesRepository).save(captor.capture());
        verify(publisher).publishCarePreferenceUpserted(captor.getValue());
    }
}
