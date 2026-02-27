package com.positivity.inventory.internal.dto.receiving;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveItemsRequest {
    @NotEmpty(message = "At least one line must be provided")
    @Valid
    private List<ReceiveLineRequest> lines;
}