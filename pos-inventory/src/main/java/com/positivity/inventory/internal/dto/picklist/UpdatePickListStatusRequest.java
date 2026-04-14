package com.positivity.inventory.internal.dto.picklist;

import com.positivity.inventory.internal.enums.PickListStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePickListStatusRequest {

    @NotNull
    private PickListStatus status;
}
