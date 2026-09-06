package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response envelope for {@code GET /v1/workorders/analytics/open-by-customer} (#1855). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Per-customer open work-order counts, with truncation signalling")
public class OpenWorkordersByCustomerResponse {

    @Schema(
            description =
                    "One row per customer with open work, ordered by openWorkorders descending, capped at `limit`")
    private List<OpenWorkorderCustomerRow> rows;

    @Schema(description = "Total customers holding open work orders, before the limit was applied")
    private int totalCustomers;

    @Schema(description = "Total open work orders across all those customers, before the limit was applied")
    private long totalOpenWorkorders;

    @Schema(description = "True when more customers had open work than `limit` allowed and the result was capped")
    private boolean truncated;

    @Schema(description = "The limit applied to this response")
    private int limit;
}
