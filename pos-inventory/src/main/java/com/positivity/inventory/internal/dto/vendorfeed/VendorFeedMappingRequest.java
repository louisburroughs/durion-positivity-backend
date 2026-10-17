package com.positivity.inventory.internal.dto.vendorfeed;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.FeedVendorType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to map a manufacturer or distributor feed vendor to a pos-supplier vendor")
public class VendorFeedMappingRequest {

    @Schema(description = "Which feed the vendor comes from", example = "MANUFACTURER", requiredMode = REQUIRED)
    @NotNull
    private FeedVendorType feedType;

    @Schema(
            description = "The feed's own vendor id, trimmed: the manufacturer UUID (stored lower-case) or the"
                    + " distributor id as its feed carries it",
            example = "01980003-0000-7000-8000-000000000004",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 255)
    private String feedVendorRef;

    @Schema(
            description = "The pos-supplier vendor the feed vendor is; it must be known to pos-inventory and ACTIVE",
            example = "01980003-0000-7000-8000-000000000009",
            requiredMode = REQUIRED)
    @NotNull
    private UUID vendorId;

    @Schema(description = "Free-text note about the mapping", example = "Same company, new legal entity")
    @Size(max = 500)
    private String note;
}
