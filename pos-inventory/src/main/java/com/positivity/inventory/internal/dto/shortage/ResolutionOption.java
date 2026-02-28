package com.positivity.inventory.internal.dto.shortage;

import com.positivity.inventory.internal.enums.ResolutionOptionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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