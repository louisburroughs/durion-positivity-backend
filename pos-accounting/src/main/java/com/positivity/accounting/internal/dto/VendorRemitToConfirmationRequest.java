package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Confirms a vendor's changed remit-to (CAP:550 S24, #2517, rule 7). The confirmer is the caller (ADR-0018); no body
 * field names them.
 */
@Schema(description = "Confirms the vendor's current remit-to version and how it was verified")
public record VendorRemitToConfirmationRequest(
        @Schema(
                description = "The remit-to version confirmed; must be the vendor's current version",
                example = "3",
                requiredMode = REQUIRED)
        @Nullable
        Integer remitToVersion,

        @Schema(
                description = "How the new remit-to was verified, at least 10 characters",
                example = "Called the vendor's accounts desk on the number on file; new address confirmed",
                requiredMode = REQUIRED)
        @Size(max = 1000)
        @Nullable
        String justification) {}
