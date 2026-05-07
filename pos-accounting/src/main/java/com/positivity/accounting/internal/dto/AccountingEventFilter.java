package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.AccountingEventStatus;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingEventFilter {

    @Nullable
    private UUID organizationId;

    @Nullable
    private String eventType;

    @Nullable
    private AccountingEventStatus status;

    @Nullable
    private String idempotencyOutcome;

    @Nullable
    private Instant receivedAtFrom;

    @Nullable
    private Instant receivedAtTo;

    @Nullable
    private UUID eventId;

    @Nullable
    private UUID ingestionId;

    @Nullable
    private String domainKeyId;

    @Nullable
    private UUID invoiceId;
}
