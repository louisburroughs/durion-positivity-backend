package com.positivity.domainevents.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class VehicleCarePreferenceUpdatedV1Test {

    private static final ObjectMapper MAPPER =
            JsonMapper.builder().findAndAddModules().build();

    private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000021");

    @Test
    void roundTripPreservesFields() {
        VehicleCarePreferenceUpdatedV1 evt =
                new VehicleCarePreferenceUpdatedV1(VEHICLE_ID, 4, false, Instant.parse("2026-07-20T10:00:00Z"));

        String json = MAPPER.writeValueAsString(evt);
        VehicleCarePreferenceUpdatedV1 back = MAPPER.readValue(json, VehicleCarePreferenceUpdatedV1.class);

        assertThat(back).isEqualTo(evt);
        assertThat(VehicleCarePreferenceUpdatedV1.EVENT_TYPE).isEqualTo("vehicle.care-preference.updated");
        assertThat(VehicleCarePreferenceUpdatedV1.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    void allowsNullIntervalAsUseDefault() {
        VehicleCarePreferenceUpdatedV1 evt =
                new VehicleCarePreferenceUpdatedV1(VEHICLE_ID, null, false, Instant.parse("2026-07-20T10:00:00Z"));
        assertThat(evt.serviceIntervalMonths()).isNull();
    }

    @Test
    void allowsDeletedTombstone() {
        VehicleCarePreferenceUpdatedV1 evt = new VehicleCarePreferenceUpdatedV1(VEHICLE_ID, null, true, null);
        assertThat(evt.deleted()).isTrue();
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThatThrownBy(() -> new VehicleCarePreferenceUpdatedV1(null, 4, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VehicleCarePreferenceUpdatedV1(VEHICLE_ID, 0, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VehicleCarePreferenceUpdatedV1(VEHICLE_ID, 121, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VehicleCarePreferenceUpdatedV1(VEHICLE_ID, 4, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
