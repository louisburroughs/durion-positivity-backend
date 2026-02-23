package com.positivity.location.internal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for mobile unit coverage rules.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CoverageRuleResponse {

    private UUID id;
    private UUID mobileUnitId;
    private UUID serviceAreaId;
    private String ruleType;
    private Integer priority;
    private LocalDate validFrom;
    private LocalDate validTo;
    private BigDecimal maxDistance;
}
