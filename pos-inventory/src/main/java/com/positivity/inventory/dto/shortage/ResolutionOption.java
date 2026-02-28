package com.positivity.inventory.dto.shortage;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResolutionOption {
    private ResolutionOptionType type;
    private String substitutePartNumber;
    private BigDecimal unitCost;
    private Integer estimatedLeadTimeDays;
    private String source;
    private String confidence;
    private String qualityTier;
}