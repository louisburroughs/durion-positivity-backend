package com.positivity.inventory.internal.dto.putaway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratePutawayTasksRequest {

    private String sourceReceiptId;
}
