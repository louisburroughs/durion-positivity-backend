package com.positivity.workorder.internal.dto.pick;

import com.positivity.workorder.internal.enums.ConsumeItemStatus;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConsumePickedItemsResponse {
    private UUID workorderId;
    private int totalItemsConsumed;
    private List<ConsumedItemResult> results;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumedItemResult {
        private UUID pickTaskId;
        private int quantityConsumed;
        private ConsumeItemStatus status;
    }
}
