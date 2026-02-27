package com.positivity.inventory.internal.dto.asn;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsnLineResponse {

    private UUID asnLineId;
    private UUID poId;
    private String sku;
    private BigDecimal quantityShipped;
    private BigDecimal quantityReceived;
    private String unitOfMeasure;
    private String lotNumber;
}
