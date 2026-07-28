package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.enums.TagAssignmentSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to attach a tag to a party")
public class AssignPartyTagRequest {

    @Schema(description = "Tag to attach", requiredMode = REQUIRED)
    @NotNull
    private UUID tagId;

    @Schema(description = "How the assignment was made; defaults to MANUAL", requiredMode = NOT_REQUIRED)
    @Nullable
    private TagAssignmentSource source;
}
