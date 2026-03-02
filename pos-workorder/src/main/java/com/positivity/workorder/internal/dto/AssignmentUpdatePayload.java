package com.positivity.workorder.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssignmentUpdatePayload {
    private UUID locationId;
    private UUID resourceId;
    private List<UUID> mechanicIds;
}
