package com.positivity.inventory.internal.dto.picklist;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePickListRequest {

    @NotNull
    private UUID workorderId;
    private Instant dueAt;
    private int priority = 0;
    private UUID reservationId;
}