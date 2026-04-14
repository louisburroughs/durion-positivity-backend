package com.positivity.inventory.internal.dto.receiving;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceivingSessionResponse {
    private UUID sessionId;
    private String sourceDocumentId;
    private String sourceDocumentType;
    private String supplierId;
    private String shipmentReference;
    private String status;
    private String entryMethod;
    private String createdByUserId;
    private Instant createdAt;
    private List<ReceivingLineResponse> lines;
}
