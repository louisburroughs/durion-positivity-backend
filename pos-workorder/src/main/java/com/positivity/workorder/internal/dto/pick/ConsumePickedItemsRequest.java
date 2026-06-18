package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
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
@Schema(description = "Request to consume previously picked items into a workorder")
public class ConsumePickedItemsRequest {

    @NotNull
    @NotEmpty
    @Valid
    @Schema(description = "Items to consume", requiredMode = REQUIRED)
    private List<ConsumeItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "A single picked item to consume")
    public static class ConsumeItem {

        @NotNull
        @Schema(
                description = "Identifier of the pick task to consume from",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        private UUID pickTaskId;

        @NotNull
        @Positive
        @Schema(description = "Quantity to consume", example = "2", requiredMode = REQUIRED)
        private Integer quantityToConsume;
    }
}
