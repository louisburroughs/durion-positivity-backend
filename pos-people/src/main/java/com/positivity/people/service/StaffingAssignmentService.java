package com.positivity.people.service;

import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.dto.UpdateStaffingAssignmentRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface StaffingAssignmentService {

    @NonNull
    StaffingAssignmentResponse create(@NonNull CreateStaffingAssignmentRequest request, @NonNull String actor);

    @NonNull
    List<StaffingAssignmentResponse> findByPersonId(@NonNull UUID personId);

    /**
     * Active assignments for a person as of today: status ACTIVE with today inside the
     * effective date range, primary assignment first. Empty when the person currently has
     * no location.
     */
    @NonNull
    List<StaffingAssignmentResponse> findActiveByPersonId(@NonNull UUID personId);

    @NonNull
    Optional<StaffingAssignmentResponse> findById(@NonNull UUID assignmentId);

    @NonNull
    Optional<StaffingAssignmentResponse> update(
            @NonNull UUID assignmentId, @NonNull UpdateStaffingAssignmentRequest request, @NonNull String actor);

    void end(@NonNull UUID assignmentId);
}
