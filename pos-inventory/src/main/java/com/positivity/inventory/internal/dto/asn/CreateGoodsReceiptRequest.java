package com.positivity.inventory.internal.dto.asn;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class CreateGoodsReceiptRequest {

    @NotNull
    private UUID poId;

    private UUID asnId;

    @NotNull
    private UUID locationId;

    @NotNull
    private List<CreateGoodsReceiptLineRequest> lines;
}
