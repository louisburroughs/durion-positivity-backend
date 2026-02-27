package com.positivity.inventory.internal.dto.asn;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
public class CreateAsnRequest {

    @NotNull
    private UUID vendorId;

    @NotNull
    @NotBlank
    private String asnReferenceNumber;

    @NotNull
    @NotEmpty
    private List<UUID> relatedPoIds;

    private LocalDate shipDate;

    private LocalDate expectedArrivalDate;

    @NotNull
    private List<CreateAsnLineRequest> lineItems;
}
