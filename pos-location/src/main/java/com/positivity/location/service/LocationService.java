package com.positivity.location.service;

import com.positivity.location.internal.dto.CreateLocationRequest;
import com.positivity.location.internal.dto.LocationDto;
import com.positivity.location.internal.dto.UpdateLocationRequest;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface LocationService {

    @NonNull
    List<LocationDto> listLocations();

    @NonNull
    LocationDto getLocationById(@NonNull UUID locationId);

    @NonNull
    LocationDto createLocation(@NonNull CreateLocationRequest request);

    @NonNull
    LocationDto updateLocation(@NonNull UUID locationId, @NonNull UpdateLocationRequest request);

    void deleteLocation(@NonNull UUID locationId);
}
