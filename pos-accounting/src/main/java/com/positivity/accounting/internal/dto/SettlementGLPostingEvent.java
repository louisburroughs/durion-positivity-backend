package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Transactional-outbox work item to post the batched settlement journal entry (story F1c, issue
 * #963). Enqueued in the same transaction that persists and matches a settlement's lines, then
 * delivered asynchronously by {@link com.positivity.accounting.internal.service.OutboxProcessor} to
 * {@link com.positivity.accounting.internal.handler.SettlementGLPostingEventHandler} — mirroring the
 * C1 payment-application flow so posting failures and period gates get retry semantics.
 *
 * <p>Carries only the settlement identity; the handler reloads the persisted settlement and lines to
 * compute matched/unmatched gross, keeping the posted entry consistent with the stored match state
 * on replays.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementGLPostingEvent {

    /** Outbox event UUID (delivery identity, not the posting idempotency key). */
    @NonNull
    @JsonProperty("eventId")
    private UUID eventId;

    /** Provider settlement id — the posting idempotency key basis. */
    @NonNull
    @JsonProperty("settlementId")
    private String settlementId;

    @NonNull
    @JsonProperty("providerCode")
    private String providerCode;

    /** Provider settlement date — the journal entry transaction date. */
    @NonNull
    @JsonProperty("settlementDate")
    private LocalDate settlementDate;
}
