package com.positivity.inventory.internal.dto.vendorfeed;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A feed vendor that cannot yet be a purchase candidate, and why")
public class UnmappedFeedVendorResponse {

    @Schema(description = "Which feed the vendor comes from", example = "DISTRIBUTOR")
    private String feedType;

    @Schema(description = "The feed's own vendor id", example = "ACME-DIST-01")
    private String feedVendorRef;

    @Schema(
            description = "UNMAPPED, VENDOR_NOT_IN_COPY or VENDOR_INACTIVE",
            allowableValues = {"UNMAPPED", "VENDOR_NOT_IN_COPY", "VENDOR_INACTIVE"})
    private String reason;

    @Schema(description = "How many distinct products the feed vendor reports", example = "42")
    private long productCount;

    @Schema(description = "When the feed vendor last reported", requiredMode = NOT_REQUIRED)
    private Instant lastReportedAt;
}
