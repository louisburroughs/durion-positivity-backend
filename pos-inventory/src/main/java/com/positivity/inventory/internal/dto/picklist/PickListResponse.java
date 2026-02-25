package com.positivity.inventory.internal.dto.picklist;

import com.positivity.inventory.internal.enums.PickListStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PickListResponse {

    private UUID pickListId;
    private UUID workorderId;
    private PickListStatus status;
    private int priority;
    private Instant dueAt;
    private Instant createdAt;
    private Instant updatedAt;
}