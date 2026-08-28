package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.service.model.ExchangeAuditAccessView;
import com.positivity.supplier.internal.service.model.ExchangeAuditPayloadView;
import com.positivity.supplier.internal.service.model.ExchangeAuditSummary;
import com.positivity.supplier.internal.service.model.PagedResponse;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Exchange-audit read contract (ADR-0026, ADR-0050 §7) — the operator-facing view of what this
 * deployment sent to suppliers and what came back.
 *
 * <h2>Two tiers of access, deliberately</h2>
 *
 * ADR-0050 §7 requires payload reads to sit behind a permission "tighter than profile admin" and to be
 * "themselves audited". This contract splits along that line:
 *
 * <ul>
 *   <li><strong>Metadata</strong> — {@link #listExchanges}, {@link #traceCorrelation},
 *       {@link #getExchange} and {@link #listAccesses}. No commercial content, so nothing is recorded.
 *       These queries never touch the encrypted columns at all, which means a row whose payload cannot
 *       be decrypted still lists and still reads normally.
 *   <li><strong>Content</strong> — {@link #readPayload} only. Every successful call writes an access
 *       record naming the caller, and the content is served <em>only</em> if that record commits.
 * </ul>
 *
 * All five operations require the {@code supplier:audit:read} permission, which is separate from
 * {@code supplier:profile:read}: a profile administrator has no implicit right to read commercial
 * traffic.
 *
 * <h2>Identity, not alias</h2>
 *
 * Every lookup takes {@code vendorProfileId}. {@code supplierRef} appears on results as a snapshot of
 * the profile's alias at exchange time and is never accepted as an input — it is renameable, so a query
 * by ref would silently miss every row written before a rename.
 *
 * <p>Audit rows have no foreign key to the profile (ADR-0050 §7), so exchanges of a since-deleted
 * profile remain fully readable. That is why the profile is a parameter here rather than an owning path
 * segment: the audit trail is not a child of configuration that may no longer exist.
 */
public interface SupplierExchangeAuditService {

    /**
     * Exchanges of one vendor profile within a time window, newest first.
     *
     * <p>The window is <strong>half-open</strong>: {@code from} inclusive, {@code to} exclusive. Adjacent
     * windows therefore tile without double-counting the attempt that lands exactly on a boundary.
     *
     * @param vendorProfileId profile identity; the profile need not still exist
     * @param from window start, inclusive
     * @param to window end, exclusive; must be after {@code from}
     * @param capability canonical capability key to narrow to, or {@code null} for all capabilities
     * @param page zero-based page index
     * @param size page size
     * @return a page of metadata summaries, newest first; empty when nothing matches
     */
    @NonNull
    PagedResponse<ExchangeAuditSummary> listExchanges(
            @NonNull UUID vendorProfileId,
            @NonNull Instant from,
            @NonNull Instant to,
            @Nullable String capability,
            int page,
            int size);

    /**
     * Every attempt sharing one correlation id, <strong>oldest first</strong>.
     *
     * <p>Ascending unlike {@link #listExchanges}, because a retry sequence only reads as a sequence in
     * the order it happened: attempt 1, then the retries it provoked.
     *
     * @param correlationId correlation id of one logical call
     * @param page zero-based page index
     * @param size page size
     * @return a page of metadata summaries, oldest first; empty when the correlation id is unknown
     */
    @NonNull
    PagedResponse<ExchangeAuditSummary> traceCorrelation(@NonNull String correlationId, int page, int size);

    /**
     * One exchange's metadata. No content, so nothing is recorded.
     *
     * @param exchangeAuditId audit row identity
     * @return the metadata summary; an unknown id surfaces as the module's typed not-found translation
     *     (404, ADR-0017)
     */
    @NonNull
    ExchangeAuditSummary getExchange(@NonNull UUID exchangeAuditId);

    /**
     * The stored payload content of one exchange — the audited read.
     *
     * <p>Writes an access record in the <strong>same transaction</strong> that serves the content
     * (ADR-0050 §7): if the record cannot be written the content is not returned, because a read with no
     * trail is precisely what §7 exists to prevent. Callers must treat a successful return as meaning the
     * read is on the record.
     *
     * <p>{@code null} payload fields are a normal state — no capture, no body, or content already purged
     * past the retention window — and are not an error. Content that exists but cannot be decrypted
     * <em>is</em> an error, and is still recorded as an access with an {@code UNREADABLE} outcome.
     *
     * @param exchangeAuditId audit row identity
     * @return the stored content, which may legitimately be empty
     */
    @NonNull
    ExchangeAuditPayloadView readPayload(@NonNull UUID exchangeAuditId);

    /**
     * Who read one exchange's content, newest first (ADR-0050 §7).
     *
     * <p>Reading this trail is metadata about reads, not content, so it is not itself recorded — the
     * alternative would make every audit inspection generate the events the next inspection has to sift
     * through.
     *
     * @param exchangeAuditId audit row identity
     * @param page zero-based page index
     * @param size page size
     * @return a page of access records, newest first; empty when the content has never been read
     */
    @NonNull
    PagedResponse<ExchangeAuditAccessView> listAccesses(@NonNull UUID exchangeAuditId, int page, int size);
}
