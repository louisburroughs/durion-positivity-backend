package com.positivity.people.service;

import com.positivity.people.internal.dto.AssignStaffRequest;
import com.positivity.people.internal.dto.CreateLocationRequest;
import com.positivity.people.internal.dto.LocationDto;
import com.positivity.people.internal.dto.PersonLocationAssignmentDto;
import com.positivity.people.internal.dto.UpdateLocationRequest;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface LocationService {

    @NonNull
    List<LocationDto> listActiveLocations();

    @NonNull
    LocationDto createLocation(@NonNull CreateLocationRequest request);

    @NonNull
    LocationDto getLocation(@NonNull UUID locationId);

    @NonNull
    LocationDto updateLocation(@NonNull UUID locationId, @NonNull UpdateLocationRequest request);

    void deleteLocation(@NonNull UUID locationId);

    @NonNull
    List<PersonLocationAssignmentDto> getAssignmentsByLocation(@NonNull UUID locationId);

    @NonNull
    PersonLocationAssignmentDto assignStaff(@NonNull UUID locationId, @NonNull AssignStaffRequest request);

    void unassignStaff(@NonNull UUID locationId, @NonNull UUID personId);
}
