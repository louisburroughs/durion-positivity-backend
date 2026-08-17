package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Marketing copy in one language, as the vendor stated it (CAP-324 #1352). */
@Schema(description = "Vendor-supplied marketing copy in one language.")
public record TreadDesignTextDto(
        @Schema(description = "Vendor's own language code, as stated.", example = "en-US") @NonNull
        String languageCode,

        @Schema(description = "Product name in that language.") @Nullable
        String name,

        @Schema(description = "Marketing description in that language.") @Nullable
        String description,

        @Schema(description = "Legal or qualifying text, when given.") @Nullable
        String footNotes) {}
