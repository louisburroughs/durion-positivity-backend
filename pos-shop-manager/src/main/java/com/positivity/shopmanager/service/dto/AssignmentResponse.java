package com.positivity.shopmanager.service.dto;

import com.positivity.shopmanager.service.enums.AssignmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Representation of a mechanic assignment returned from the API. */
@Value
@Builder
public class AssignmentResponse {
    UUID assignmentId;
    UUID appointmentId;
    List<AssignedMechanicInfo> mechanics;
    UUID resourceId;
    String resourceType;
    AssignmentStatus status;
    boolean override;
    Instant createdAt;
}
