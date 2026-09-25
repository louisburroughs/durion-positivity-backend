package com.positivity.workorder.internal.dto.pick;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Request to resolve a scanned product and location against a pick task. Exactly one of "
                + "scannedSkuId/scannedProductCode must be supplied, and exactly one of "
                + "scannedLocationId/scannedLocationCode (#2217).")
public class ResolveScanRequest {
    @Schema(
            description = "Identifier of the SKU that was scanned; mutually exclusive with scannedProductCode",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = NOT_REQUIRED)
    private UUID scannedSkuId;

    @Schema(
            description = "The scannable EAN/UPC code read off a barcode scan; mutually exclusive with scannedSkuId. "
                    + "Compared case-insensitively, trimmed (#2217).",
            example = "0123456789012",
            requiredMode = NOT_REQUIRED)
    private String scannedProductCode;

    @Schema(
            description = "Identifier of the location where the scan occurred; mutually exclusive with "
                    + "scannedLocationCode",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = NOT_REQUIRED)
    private UUID scannedLocationId;

    @Schema(
            description = "The location's human-readable name or barcode read off a scan; mutually exclusive with "
                    + "scannedLocationId. Compared case-insensitively, trimmed, against both the location's "
                    + "name and its barcode (#2217).",
            example = "Aisle 3 Bin 7",
            requiredMode = NOT_REQUIRED)
    private String scannedLocationCode;

    @AssertTrue(message = "Exactly one of scannedSkuId or scannedProductCode must be provided")
    boolean isProductTargetValid() {
        return (scannedSkuId != null) != hasText(scannedProductCode);
    }

    @AssertTrue(message = "Exactly one of scannedLocationId or scannedLocationCode must be provided")
    boolean isLocationTargetValid() {
        return (scannedLocationId != null) != hasText(scannedLocationCode);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
