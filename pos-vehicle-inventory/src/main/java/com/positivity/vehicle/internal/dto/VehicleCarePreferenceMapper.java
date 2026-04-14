package com.positivity.vehicle.internal.dto;

import com.positivity.vehicle.internal.entity.VehicleCarePreference;

public final class VehicleCarePreferenceMapper {
    private VehicleCarePreferenceMapper() {}

    public static VehicleCarePreferenceResponse toResponse(VehicleCarePreference preference) {
        return VehicleCarePreferenceResponse.builder()
                .id(preference.getId())
                .vehicleId(preference.getVehicleId())
                .preferences(preference.getPreferences())
                .serviceNotes(preference.getServiceNotes())
                .createdByUserId(preference.getCreatedByUserId())
                .updatedByUserId(preference.getUpdatedByUserId())
                .createdAt(preference.getCreatedAt())
                .updatedAt(preference.getUpdatedAt())
                .version(preference.getVersion())
                .build();
    }
}
