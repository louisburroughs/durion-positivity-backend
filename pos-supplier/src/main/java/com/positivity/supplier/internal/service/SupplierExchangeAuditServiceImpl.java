package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.AuditAccessEntity;
import com.positivity.supplier.internal.entity.AuditPayloadCipher;
import com.positivity.supplier.internal.entity.ExchangeAuditRawPayloadEntity;
import com.positivity.supplier.internal.enums.AuditPayloadOutcome;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.exception.PayloadUnreadableException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.AuditAccessRepository;
import com.positivity.supplier.internal.repository.ExchangeAuditMetadata;
import com.positivity.supplier.internal.repository.ExchangeAuditRawPayloadRepository;
import com.positivity.supplier.internal.repository.ExchangeAuditRepository;
import com.positivity.supplier.service.SupplierExchangeAuditService;
import com.positivity.supplier.service.model.ExchangeAuditAccessView;
import com.positivity.supplier.service.model.ExchangeAuditPayloadView;
import com.positivity.supplier.service.model.ExchangeAuditSummary;
import com.positivity.supplier.service.model.PagedResponse;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exchange-audit read API (ADR-0050 §7).
 *
 * <h2>Metadata is read without decrypting anything</h2>
 *
 * Every metadata query goes through {@link ExchangeAuditMetadata}, whose {@code SELECT} clause does not
 * name the payload columns, so {@code EncryptedPayloadConverter} never runs on these paths. Three
 * consequences, all of them wanted: listings do no crypto work, no plaintext exists in the JVM for a
 * request that asked for none, and — most importantly — a row whose payload cannot be decrypted still
 * lists and still reads, which is exactly when an operator needs to find it.
 *
 * <h2>Content is decrypted here, explicitly, and never during hydration</h2>
 *
 * {@link #readPayload} loads the metadata first, then the still-encrypted bytes through
 * {@link ExchangeAuditRawPayloadEntity}, and calls {@link AuditPayloadCipher} itself. The order is
 * load-bearing rather than stylistic: ADR-0050 §7 requires an unreadable payload to be recorded as an
 * access too, and doing the decryption inside Hibernate's entity hydration would raise the failure from
 * inside the persistence context, with the identity needed to write that record trapped in the load that
 * just failed. Doing it here makes the failure an ordinary exception with the session untouched, so the
 * record still writes and still commits.
 */
@Service
public class SupplierExchangeAuditServiceImpl implements SupplierExchangeAuditService {

    private final ExchangeAuditRepository exchangeAuditRepository;
    private final ExchangeAuditRawPayloadRepository rawPayloadRepository;
    private final AuditAccessRepository accessRepository;
    private final AuditAccessRecorder accessRecorder;
    private final AuditPayloadCipher cipher;

    public SupplierExchangeAuditServiceImpl(
            @NonNull ExchangeAuditRepository exchangeAuditRepository,
            @NonNull ExchangeAuditRawPayloadRepository rawPayloadRepository,
            @NonNull AuditAccessRepository accessRepository,
            @NonNull AuditAccessRecorder accessRecorder,
            @NonNull AuditPayloadCipher cipher) {
        this.exchangeAuditRepository = Objects.requireNonNull(exchangeAuditRepository, "exchangeAuditRepository");
        this.rawPayloadRepository = Objects.requireNonNull(rawPayloadRepository, "rawPayloadRepository");
        this.accessRepository = Objects.requireNonNull(accessRepository, "accessRepository");
        this.accessRecorder = Objects.requireNonNull(accessRecorder, "accessRecorder");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    // ── Metadata reads: no content, nothing recorded ─────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public PagedResponse<ExchangeAuditSummary> listExchanges(
            @NonNull UUID vendorProfileId,
            @NonNull Instant from,
            @NonNull Instant to,
            @Nullable String capability,
            int page,
            int size) {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        requireUsableWindow(from, to);
        Pageable pageable = PageRequest.of(page, size);

        Page<ExchangeAuditMetadata> found = capability == null
                ? exchangeAuditRepository.findMetadataByProfileAndWindow(vendorProfileId, from, to, pageable)
                : exchangeAuditRepository.findMetadataByProfileCapabilityAndWindow(
                        vendorProfileId, parseCapability(capability), from, to, pageable);
        return toPagedResponse(found, SupplierExchangeAuditServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public PagedResponse<ExchangeAuditSummary> traceCorrelation(@NonNull String correlationId, int page, int size) {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        return toPagedResponse(
                exchangeAuditRepository.findMetadataByCorrelationId(correlationId, PageRequest.of(page, size)),
                SupplierExchangeAuditServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public ExchangeAuditSummary getExchange(@NonNull UUID exchangeAuditId) {
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");
        return toSummary(requireMetadata(exchangeAuditId));
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public PagedResponse<ExchangeAuditAccessView> listAccesses(@NonNull UUID exchangeAuditId, int page, int size) {
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");
        // Deliberately no existence check on the exchange: access records outlive nothing here, but they
        // are keyed by a plain UUID with no foreign key, so an exchange row that was never written and
        // one that has no reads are indistinguishable — and both correctly answer "nobody read it".
        return toPagedResponse(
                accessRepository.findByExchangeAuditIdOrderByAccessedAtDesc(
                        exchangeAuditId, PageRequest.of(page, size)),
                SupplierExchangeAuditServiceImpl::toAccessView);
    }

    // ── The audited content read ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <h2>Transaction semantics, which are the whole feature</h2>
     *
     * <p><strong>Not {@code readOnly}.</strong> This method writes the access record, and marking it
     * read-only would set the JDBC connection read-only and fail that insert — turning the tightest
     * guarantee in the module into a runtime error. It looks like a read; it is not one.
     *
     * <p><strong>{@code noRollbackFor = PayloadUnreadableException}.</strong> An unreadable payload is a
     * read that happened and produced nothing usable, and it is the case ADR-0050 §7 most wants recorded.
     * Letting it roll back would delete the evidence of the very event worth investigating.
     * {@link AuditAccessRecorder} documents the other side of this.
     */
    @Override
    @Transactional(noRollbackFor = PayloadUnreadableException.class)
    @NonNull
    public ExchangeAuditPayloadView readPayload(@NonNull UUID exchangeAuditId) {
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");

        // 1. Metadata first, so the access record can name the profile and capability even when the
        //    content turns out to be unreadable. Nothing here decrypts.
        ExchangeAuditMetadata metadata = requireMetadata(exchangeAuditId);

        // 2. The still-encrypted bytes. Same table, same transaction, so this cannot miss.
        ExchangeAuditRawPayloadEntity raw =
                rawPayloadRepository.findById(exchangeAuditId).orElseThrow(() -> notFound(exchangeAuditId));

        // 3. Decrypt explicitly. A failure here is an ordinary exception in application code, not one
        //    raised from inside entity hydration, which is what keeps step 4 possible.
        String requestPayload = null;
        String responsePayload = null;
        PayloadUnreadableException failure = null;
        try {
            requestPayload = decryptOrNull(raw.getRequestPayload());
            responsePayload = decryptOrNull(raw.getResponsePayload());
        } catch (PayloadUnreadableException ex) {
            // Partial content is not served: half a document plus a success status would present an
            // integrity failure as a normal result, and a failed GCM tag is potential tampering evidence.
            failure = ex;
            requestPayload = null;
            responsePayload = null;
        }

        AuditPayloadOutcome outcome = classify(raw, failure);

        // 4. Record the read, in this transaction, with no catch. If this fails, nothing is served.
        accessRecorder.recordPayloadRead(exchangeAuditId, metadata.vendorProfileId(), metadata.capability(), outcome);

        if (failure != null) {
            // The access row survives this: see noRollbackFor above.
            throw failure;
        }

        PayloadCaptureLevel captureLevel = metadata.captureLevel();
        return new ExchangeAuditPayloadView(
                exchangeAuditId,
                com.positivity.supplier.service.model.PayloadCaptureLevel.valueOf(captureLevel.name()),
                captureLevel == PayloadCaptureLevel.REDACTED,
                requestPayload,
                responsePayload);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Which of the three honest outcomes this read produced.
     *
     * <p>{@code NOT_CAPTURED} is checked against the <em>raw</em> columns rather than the capture level:
     * a {@code FULL} binding still stores nothing when the vendor sent no body, and retention nulls
     * content while leaving the level intact. The stored bytes are the only thing that knows.
     */
    @NonNull
    private static AuditPayloadOutcome classify(
            @NonNull ExchangeAuditRawPayloadEntity raw, @Nullable PayloadUnreadableException failure) {
        if (failure != null) {
            return AuditPayloadOutcome.UNREADABLE;
        }
        boolean anyContent = raw.getRequestPayload() != null || raw.getResponsePayload() != null;
        return anyContent ? AuditPayloadOutcome.DECRYPTED : AuditPayloadOutcome.NOT_CAPTURED;
    }

    @Nullable
    private String decryptOrNull(byte @Nullable [] envelope) {
        // null is a normal state, not an error: no capture, no body, or content already purged.
        return envelope == null ? null : cipher.decrypt(envelope);
    }

    @NonNull
    private ExchangeAuditMetadata requireMetadata(@NonNull UUID exchangeAuditId) {
        return exchangeAuditRepository.findMetadataById(exchangeAuditId).orElseThrow(() -> notFound(exchangeAuditId));
    }

    @NonNull
    private static SupplierNotFoundException notFound(@NonNull UUID exchangeAuditId) {
        return new SupplierNotFoundException(
                SupplierNotFoundException.EXCHANGE_AUDIT_NOT_FOUND,
                "No supplier exchange-audit record with id " + exchangeAuditId);
    }

    private static void requireUsableWindow(@NonNull Instant from, @NonNull Instant to) {
        // The window is half-open, so from == to matches nothing. Rejecting it beats silently returning
        // an empty page that looks like "this supplier had no traffic".
        if (!to.isAfter(from)) {
            throw new SupplierValidationException(
                    SupplierValidationException.AUDIT_WINDOW_INVALID,
                    "Audit window end must be after its start; the window is half-open"
                            + " (from inclusive, to exclusive), so " + from + ".." + to + " can never match.");
        }
    }

    /**
     * Canonical capability key to enum. An unknown key is a typed 400 rather than an empty page:
     * a filter that silently matches nothing turns a typo into "this integration was never used".
     *
     * <p>Intentionally a local twin of {@code SupplierProfileAdminServiceImpl.parseCapability} — six
     * lines of guard clause, not worth a shared abstraction, and {@code SupplierContractKeyParityTest}
     * already pins the key set both of them accept.
     */
    @NonNull
    private static SupplierCapability parseCapability(@NonNull String capability) {
        try {
            return SupplierCapability.valueOf(capability);
        } catch (IllegalArgumentException ex) {
            throw new SupplierValidationException(
                    SupplierValidationException.UNKNOWN_CAPABILITY,
                    "'" + capability + "' is not a canonical supplier capability key");
        }
    }

    @NonNull
    private static <S, T> PagedResponse<T> toPagedResponse(@NonNull Page<S> page, @NonNull Function<S, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @NonNull
    private static ExchangeAuditSummary toSummary(@NonNull ExchangeAuditMetadata metadata) {
        return new ExchangeAuditSummary(
                metadata.exchangeAuditId(),
                metadata.vendorProfileId(),
                metadata.supplierRef(),
                metadata.bindingId(),
                metadata.capability().name(),
                metadata.protocolFamily().name(),
                metadata.protocolVersion(),
                metadata.httpMethod(),
                metadata.endpointUri(),
                metadata.attempt(),
                metadata.correlationId(),
                metadata.outcome(),
                metadata.httpStatus(),
                metadata.startedAt(),
                metadata.durationMs(),
                metadata.failureDetail(),
                com.positivity.supplier.service.model.PayloadCaptureLevel.valueOf(
                        metadata.captureLevel().name()),
                metadata.requestPayloadPresent(),
                metadata.responsePayloadPresent(),
                metadata.payloadsPurgedAt(),
                metadata.createdBy());
    }

    @NonNull
    private static ExchangeAuditAccessView toAccessView(@NonNull AuditAccessEntity entity) {
        return new ExchangeAuditAccessView(
                entity.getAuditAccessId(),
                entity.getExchangeAuditId(),
                entity.getVendorProfileId(),
                entity.getCapability().name(),
                entity.getAccessedBy(),
                entity.getAccessedAt(),
                com.positivity.supplier.service.model.AuditAccessKind.valueOf(
                        entity.getAccessKind().name()),
                com.positivity.supplier.service.model.AuditPayloadOutcome.valueOf(
                        entity.getPayloadOutcome().name()));
    }
}
