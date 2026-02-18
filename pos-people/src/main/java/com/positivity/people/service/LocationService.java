package com.positivity.people.service;

import com.positivity.people.internal.dto.AssignStaffRequest;
import com.positivity.people.internal.dto.PersonLocationAssignmentDto;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface LocationService {

    @NonNull
    List<PersonLocationAssignmentDto> getAssignmentsByLocation(@NonNull UUID locationId);

    @NonNull
    PersonLocationAssignmentDto assignStaff(@NonNull UUID locationId, @NonNull AssignStaffRequest request);

    void unassignStaff(@NonNull UUID locationId, @NonNull UUID personId);
}
