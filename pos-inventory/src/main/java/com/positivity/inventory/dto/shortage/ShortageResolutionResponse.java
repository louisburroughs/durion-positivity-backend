package com.positivity.inventory.dto.shortage;

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
public class ShortageResolutionResponse {
    private UUID allocationId;
    private String sku;
    private List<ResolutionOption> options;
    private boolean partialResultsBanner;
}