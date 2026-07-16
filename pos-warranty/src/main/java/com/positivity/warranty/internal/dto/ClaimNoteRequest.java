package com.positivity.warranty.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Free-form staff note, append-only (PRD §3.9). */
@Schema(description = "Staff note to append to a warranty claim")
public record ClaimNoteRequest(
        @Schema(description = "Note text") @NotBlank @Size(max = 10_000)
        String note) {}
