package com.positivity.inventory.internal.dto.putaway;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratePutawayTasksRequest {

    @NotBlank
    private String sourceReceiptId;

    /**
     * Legacy single-line request field.
     * Prefer using lineItems for new integrations.
     */
    private String productId;

    /**
     * Legacy single-line request field.
     * Prefer using lineItems for new integrations.
     */
    @Min(1)
    private Integer quantity;

    @Valid
    private List<PutawayLineItemRequest> lineItems;
}
