package com.positivity.supplier.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The stored payload content of one exchange-audit row (ADR-0050 §7).
 *
 * <p>Returning this record is itself an audited event: every successful call to the payload endpoint
 * writes a {@code supplier_audit_access} row naming the caller, the exchange, and the time — and the
 * payload is served <strong>only</strong> if that record commits. A caller holding this object can
 * assume the read is on the record.
 *
 * <p>{@code null} payload fields are a normal state, not an error: a {@code METADATA_ONLY} binding
 * captured none, the vendor may have sent no body, and retention nulls content after 400 days while
 * keeping the metadata row. {@link ExchangeAuditSummary#payloadsPurgedAt()} and {@link #captureLevel()}
 * together say which of those applies.
 *
 * @param exchangeAuditId the exchange whose content this is
 * @param captureLevel the capture level applied when the row was written
 * @param redacted whether the content below was altered before storage. When {@code true} these are
 *     <em>not</em> the wire documents: sensitive fields were replaced at capture time and the original
 *     values were never persisted, so they cannot be recovered from here or anywhere else
 * @param requestPayload the stored request document, or {@code null} when none is stored
 * @param responsePayload the stored response document, or {@code null} when none is stored
 */
@Schema(
        description = "Stored payload content of one exchange-audit row. Retrieving it writes an access record"
                + " naming the caller; the content is served only if that record commits.")
public record ExchangeAuditPayloadView(
        @Schema(
                description = "Identity of the exchange-audit row this content belongs to.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70")
        @NonNull
        UUID exchangeAuditId,

        @Schema(
                description = "The payload capture level applied when this row was written. METADATA_ONLY rows carry"
                        + " no content at all.",
                example = "REDACTED")
        @NonNull
        PayloadCaptureLevel captureLevel,

        @Schema(
                description = "Whether the content below was altered before storage. When true these are NOT the wire"
                        + " documents: sensitive fields were replaced with a redaction marker at capture time and the"
                        + " original values were never persisted, so they cannot be recovered from anywhere.",
                example = "true")
        boolean redacted,

        @Schema(
                description = "Stored request document. Null is a normal state, not an error -- a METADATA_ONLY"
                        + " binding, a request with no body, or a row whose content retention has already purged.",
                example = "<StockInquiry><Item>205/55R16</Item></StockInquiry>")
        @Nullable
        String requestPayload,

        @Schema(
                description = "Stored response document. Null is a normal state, not an error -- see requestPayload.",
                example = "<StockInquiryResponse><Available>4</Available></StockInquiryResponse>")
        @Nullable
        String responsePayload) {

    public ExchangeAuditPayloadView {
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");
        Objects.requireNonNull(captureLevel, "captureLevel must not be null");
    }
}
