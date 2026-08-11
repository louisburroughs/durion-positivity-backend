package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to pin parties into a static segment")
public class SegmentMembersRequest {

    @Schema(description = "Parties to add; already-present parties are ignored", requiredMode = REQUIRED)
    @NotEmpty
    @Size(max = 5000)
    private List<UUID> partyIds;
}
