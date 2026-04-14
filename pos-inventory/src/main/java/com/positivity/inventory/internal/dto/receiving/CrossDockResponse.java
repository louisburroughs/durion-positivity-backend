package com.positivity.inventory.internal.dto.receiving;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrossDockResponse {
    private UUID lineId;
    private String workorderId;
    private String workorderLineId;
    private BigDecimal crossDockedQuantity;
    private List<String> ledgerEntryIds;
    private String sessionStatus;
    private String lineStatus;
}
