package com.positivity.inventory.internal.dto.picklist;

import com.positivity.inventory.internal.enums.PickTaskStatus;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PickTaskResponse {

    private UUID pickTaskId;
    private UUID pickListId;
    private UUID skuId;
    private UUID locationId;
    private int quantityRequired;
    private int quantityPicked;
    private PickTaskStatus status;
    private int sortOrder;
}
