package com.positivity.accounting.internal.bankfeed.file.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Discard of an import (SPEC §3.8, §6.1; story S3, #2302). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Discard of an import that will not be committed")
public class BankImportDiscardRequest {

    @Schema(
            description = "Why the import is discarded (at least 10 characters)",
            example = "Uploaded the savings account's file by mistake",
            requiredMode = REQUIRED)
    private String reason;

    @Schema(description = "The import version the discard was made against", example = "3")
    private Long version;
}
