package com.positivity.inventory.internal.dto.asn;

import com.positivity.inventory.internal.enums.AsnStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AsnResponse {

    private UUID asnId;
    private String asnReferenceNumber;
    private UUID vendorId;
    private AsnStatus status;
    private LocalDate shipDate;
    private LocalDate expectedArrivalDate;
    private String createdBy;
    private Instant createdAt;
    private List<AsnLineResponse> lineItems;
}
