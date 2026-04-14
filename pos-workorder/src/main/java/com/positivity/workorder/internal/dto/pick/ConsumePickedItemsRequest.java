package com.positivity.workorder.internal.dto.pick;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
public class ConsumePickedItemsRequest {
    @NotNull
    @NotEmpty
    private List<ConsumeItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumeItem {
        @NotNull
        private UUID pickTaskId;

        @NotNull
        @Positive
        private Integer quantityToConsume;
    }
}
