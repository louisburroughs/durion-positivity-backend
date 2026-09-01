package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A note about the customer, recorded against a workorder")
public class AddWorkorderNoteRequest {

    @Size(max = 100)
    @Schema(
            description = "Caller-supplied classification of the note. Free text; the shops' vocabularies differ "
                    + "and CRM only displays it.",
            example = "CUSTOMER_REQUEST",
            requiredMode = NOT_REQUIRED)
    private String noteType;

    @NotBlank
    @Size(max = 2000)
    @Schema(
            description = "The note as written.",
            example = "Customer says the noise only happens on a cold start.",
            requiredMode = REQUIRED)
    private String noteText;
}
