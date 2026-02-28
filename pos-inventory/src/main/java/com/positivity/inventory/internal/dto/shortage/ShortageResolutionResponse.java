package com.positivity.inventory.internal.dto.shortage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

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