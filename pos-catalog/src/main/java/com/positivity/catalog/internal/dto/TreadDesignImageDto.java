package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One piece of artwork on a tread design (CAP-324 #1352). {@code unresolved} true means the bytes
 * are still missing and will arrive on a later republication — never that the artwork does not
 * exist.
 */
@Schema(description = "Vendor-supplied artwork reference.")
public record TreadDesignImageDto(
        @Schema(description = "Vendor's own classification of the image.") @Nullable
        String imageType,

        @Schema(description = "pos-image identifier for the stored bytes; absent when unresolved.") @Nullable
        Long imageId,

        @Schema(description = "Whether the artwork is still missing and awaiting a future republication.")
        boolean unresolved) {}
