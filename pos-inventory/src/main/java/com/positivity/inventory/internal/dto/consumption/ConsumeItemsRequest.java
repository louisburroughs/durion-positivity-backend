package com.positivity.inventory.internal.dto.consumption;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumeItemsRequest {

    @NotNull
    private UUID workorderId;

    private UUID pickListId;
    private List<ConsumeItemLine> items;
}
