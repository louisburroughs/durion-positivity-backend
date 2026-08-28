package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.service.dto.AssignmentResponse;
import com.positivity.shopmanager.internal.service.dto.CreateAssignmentRequest;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface AssignmentService {
    @NonNull
    AssignmentResponse create(@NonNull CreateAssignmentRequest request);

    @NonNull
    List<AssignmentResponse> getByAppointmentId(@NonNull UUID appointmentId);
}
