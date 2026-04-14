package com.positivity.inventory.internal.dto.receiving;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReceivingSessionRequest {
    @NotBlank(message = "sourceDocumentId is required")
    private String sourceDocumentId;

    private String entryMethod;
}
