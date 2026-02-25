package com.positivity.inventory.internal.dto.returns;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnItemsRequest {

    @NotNull
    private UUID workorderId;

    @NotBlank
    private String returnReason;

    @Valid
    @NotEmpty
    private List<ReturnItemLine> items;
}
