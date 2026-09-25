package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Result of resolving a scanned SKU and location against a pick task")
public class ResolveScanResponse {
    @NotNull
    @Schema(
            description = "Identifier of the pick task the scan resolved against",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID pickTaskId;

    @NotNull
    @Schema(
            description = "Identifier of the pick list containing the resolved task",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = REQUIRED)
    private UUID pickListId;

    @Schema(
            description =
                    "Identifier of the SKU resolved for the scan (may differ from scanned SKU after substitution)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID resolvedSkuId;

    @Schema(
            description = "Identifier of the location resolved for the scan",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID resolvedLocationId;

    @Schema(
            description = "The task's expected scannable product code, so the UI can tell the mechanic what "
                    + "was expected. Null when the task carries no replicated product code.",
            example = "0123456789012",
            requiredMode = NOT_REQUIRED)
    private String expectedProductCode;

    @Schema(
            description = "The task's expected location name, so the UI can tell the mechanic what was "
                    + "expected. Null when the task carries no replicated location name.",
            example = "Aisle 3 Bin 7",
            requiredMode = NOT_REQUIRED)
    private String expectedLocationCode;

    @Schema(
            description = "The task's expected location barcode, so the UI can tell the mechanic what was "
                    + "expected. Null when the task carries no replicated location barcode.",
            example = "LOC-0037",
            requiredMode = NOT_REQUIRED)
    private String expectedLocationBarcode;

    @Schema(
            description = "Whether the scan matched the expected SKU and location for the pick task",
            example = "true",
            requiredMode = REQUIRED)
    private boolean matched;

    @Schema(
            description = "Status describing the match outcome, backed by MatchStatus: MATCHED (both the "
                    + "product and location scans matched); SKU_MISMATCH (location matched, product did not); "
                    + "LOCATION_MISMATCH (product matched, location did not); NO_MATCH (neither matched); "
                    + "PRODUCT_CODE_UNAVAILABLE (a product code was scanned but the task carries no replicated "
                    + "code to compare against — this cannot verify, it is not necessarily wrong); "
                    + "LOCATION_CODE_UNAVAILABLE (same, for a scanned location code). Unknown-vs-wrong on a code "
                    + "scan cannot be distinguished further without a synchronous catalog/location lookup, which "
                    + "ADR-0044 forbids here; ambiguity across tasks is not a concern because comparison is "
                    + "scoped to one task and EAN/UPC codes are unique per tenant (ADR-0053 §5).",
            example = "MATCHED",
            allowableValues = {
                "MATCHED",
                "SKU_MISMATCH",
                "LOCATION_MISMATCH",
                "NO_MATCH",
                "PRODUCT_CODE_UNAVAILABLE",
                "LOCATION_CODE_UNAVAILABLE"
            },
            requiredMode = NOT_REQUIRED)
    private String matchStatus;

    /** The finite set of {@link #matchStatus} values (#2217); see the field's {@code @Schema} for meaning. */
    public enum MatchStatus {
        MATCHED,
        SKU_MISMATCH,
        LOCATION_MISMATCH,
        NO_MATCH,
        PRODUCT_CODE_UNAVAILABLE,
        LOCATION_CODE_UNAVAILABLE
    }
}
