package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "Request to add a product to a substitution group")
public class SubstitutionGroupMemberRequestDto {

    @NotNull
    @Schema(
            description = "Identifier of the product to add",
            example = "018e1c9f-6b5a-7890-abcd-1234567890ab",
            requiredMode = REQUIRED)
    private UUID productId;
}
