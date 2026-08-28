package com.positivity.mcp.internal.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiRequest;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.observability.NltiSpanAttributes;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.internal.security.PermissionCodes;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.WriteSignal;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryPublisher;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NltiRequestServiceImpl implements NltiRequestService {

    /** {@code outcome.status} values, matching the chat path's vocabulary. */
    static final String TELEMETRY_STATUS_SUCCESS = "SUCCESS";

    static final String TELEMETRY_STATUS_ERROR = "ERROR";

    private final NltiSessionRepository sessionRepository;
    private final NltiRequestRepository requestRepository;
    private final IntentParserService intentParserService;
    private final NltiWritePlanService writePlanService;
    private final NltiRequestTelemetryPublisher telemetryPublisher;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final Counter requestCount;
    private final Counter errorCount;
    private final Timer requestLatency;

    @Value("${pos.nlti.session.ttl-hours:24}")
    private long sessionTtlHours = 24;

    @Value("${pos.nlti.rate-limit.per-session:100}")
    private int perSessionRateLimit = 100;

    @Value("${pos.nlti.rate-limit.max-tracked-sessions:100000}")
    private long maxTrackedSessions = 100_000;

    private final AtomicReference<Cache<UUID, AtomicInteger>> sessionRequestCountsRef = new AtomicReference<>();
    private final Counter rateLimitCounterEvictions;
    private final Counter rateLimitCounterInvalidations;

    public NltiRequestServiceImpl(
            @NonNull NltiSessionRepository sessionRepository,
            @NonNull NltiRequestRepository requestRepository,
            @NonNull IntentParserService intentParserService,
            @NonNull NltiWritePlanService writePlanService,
            @NonNull NltiRequestTelemetryPublisher telemetryPublisher,
            @NonNull Clock clock,
            @NonNull MeterRegistry meterRegistry) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.requestRepository = Objects.requireNonNull(requestRepository, "requestRepository must not be null");
        this.intentParserService = Objects.requireNonNull(intentParserService, "intentParserService must not be null");
        this.writePlanService = Objects.requireNonNull(writePlanService, "writePlanService must not be null");
        this.telemetryPublisher = Objects.requireNonNull(telemetryPublisher, "telemetryPublisher must not be null");
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.requestCount = meterRegistry.counter("nlt.request.count");
        this.errorCount = meterRegistry.counter("nlt.error.count");
        this.requestLatency = meterRegistry.timer("nlt.request.latency_ms");
        this.rateLimitCounterEvictions = meterRegistry.counter("nlt.rate_limit.session_counter.evictions");
        this.rateLimitCounterInvalidations = meterRegistry.counter("nlt.rate_limit.session_counter.invalidations");
    }

    @Override
    public @NonNull NltiResponseV1 submit(@NonNull NltiRequestDTO request) {
        return submit(request, null);
    }

    @Override
    public @NonNull NltiResponseV1 submit(@NonNull NltiRequestDTO request, @Nullable UUID correlationId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        long startedAtNanos = System.nanoTime();
        UUID effectiveCorrelationId = (correlationId != null) ? correlationId : UUIDv7Generator.generate();
        // #1397: the fields the telemetry event needs are filled in as the request progresses, so
        // the finally-block emission below describes how far the request actually got — an error
        // before session resolution still produces a record, just with fewer fields populated.
        TelemetryState telemetry = new TelemetryState();
        try {
            requestCount.increment();

            @NonNull String subjectId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
            UUID resolvedSessionId = resolveSession(request.sessionId(), subjectId);
            telemetry.sessionId = resolvedSessionId;

            Span currentSpan = Span.current();
            @NonNull AttributeKey<String> correlationIdKey = NltiSpanAttributes.NLT_CORRELATION_ID;
            @NonNull String correlationIdValue = effectiveCorrelationId.toString();
            @NonNull AttributeKey<String> userIdKey = NltiSpanAttributes.NLT_USER_ID;
            @NonNull String userIdValue = subjectId;
            setNonNullSpanAttribute(currentSpan, correlationIdKey, correlationIdValue);
            setNonNullSpanAttribute(currentSpan, userIdKey, userIdValue);

            AtomicInteger count = sessionRequestCounts().get(resolvedSessionId, key -> new AtomicInteger(0));
            if (count.incrementAndGet() > perSessionRateLimit) {
                count.decrementAndGet();
                throw new RateLimitExceededException("Rate limit exceeded for session: " + resolvedSessionId);
            }

            String promptHash = hashPrompt(request.prompt());
            UUID newRequestId = UUIDv7Generator.generate();
            NltiRequest nltiRequest = new NltiRequest();
            nltiRequest.setId(newRequestId);
            nltiRequest.setCorrelationId(effectiveCorrelationId);
            nltiRequest.setSessionId(resolvedSessionId);
            nltiRequest.setStatus(NltiRequestStatus.ACCEPTED);
            nltiRequest.setPromptHash(promptHash);
            requestRepository.save(nltiRequest);
            telemetry.requestId = newRequestId;

            @NonNull AttributeKey<String> requestIdKey = NltiSpanAttributes.NLT_REQUEST_ID;
            @NonNull String requestIdValue = newRequestId.toString();
            @NonNull AttributeKey<String> sessionIdKey = NltiSpanAttributes.NLT_SESSION_ID;
            @NonNull String sessionIdValue = resolvedSessionId.toString();
            setNonNullSpanAttribute(currentSpan, requestIdKey, requestIdValue);
            setNonNullSpanAttribute(currentSpan, sessionIdKey, sessionIdValue);

            // Gate 6 (#1193): an ACTION-classified request yields a persisted write-plan preview —
            // never a direct execution. QUERY/UNKNOWN requests keep the plain ACCEPTED envelope.
            IntentV1 intent = intentParserService.parse(request.prompt(), resolvedSessionId, effectiveCorrelationId);
            telemetry.intentType = intent.intentType();
            telemetry.riskLevel = intent.riskLevel();
            if (NltiIntentType.ACTION.name().equals(intent.intentType())) {
                // The request reached the write gate: mark it as a write whatever the gate then
                // decides (plan preview, reused pending plan, or NEEDS_CLARIFICATION), so
                // write.isWrite counts write intents detected, not writes ultimately performed.
                telemetry.isWrite = true;
                Set<String> callerPermissionCodes = PermissionCodes.extract(SecurityContextHelper.getAuthorities());
                return writePlanService.previewAction(nltiRequest, request, intent, callerPermissionCodes);
            }

            return new NltiResponseV1(newRequestId, effectiveCorrelationId, resolvedSessionId, "ACCEPTED", null, null);
        } catch (Exception exception) {
            errorCount.increment();
            telemetry.status = TELEMETRY_STATUS_ERROR;
            telemetry.errorCode = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            sample.stop(requestLatency);
            telemetryPublisher.emit(
                    effectiveCorrelationId,
                    telemetry.sessionId,
                    telemetry.requestId,
                    telemetry.intentType,
                    telemetry.riskLevel,
                    telemetry.isWrite ? new WriteSignal(true, null, null) : null,
                    Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis(),
                    telemetry.status,
                    telemetry.errorCode);
        }
    }

    /** Mutable accumulator for the telemetry fields {@link #submit} fills in as it progresses. */
    private static final class TelemetryState {
        private @Nullable UUID sessionId;
        private @Nullable UUID requestId;
        private @Nullable String intentType;
        private @Nullable String riskLevel;
        private boolean isWrite;
        private String status = TELEMETRY_STATUS_SUCCESS;
        private @Nullable String errorCode;
    }

    @SuppressWarnings("null")
    private static void setNonNullSpanAttribute(Span span, AttributeKey<String> key, String value) {
        span.setAttribute(key, value);
    }

    private @NonNull UUID resolveSession(@Nullable UUID providedSessionId, @NonNull String subjectId) {
        if (providedSessionId == null) {
            return createAndSaveNewSession(subjectId);
        }
        Optional<NltiSession> sessionForSubject = sessionRepository.findByIdAndSubjectId(providedSessionId, subjectId);
        if (sessionForSubject.isPresent()) {
            NltiSession existing = sessionForSubject.get();
            boolean expired =
                    existing.getUpdatedAt().isBefore(OffsetDateTime.now(clock).minusHours(sessionTtlHours));
            if (expired) {
                sessionRequestCounts().invalidate(existing.getId());
                rateLimitCounterInvalidations.increment();
                return createAndSaveNewSession(subjectId);
            }
            return existing.getId();
        }

        if (sessionRepository.findById(providedSessionId).isPresent()) {
            throw new SessionOwnershipViolationException(
                    "Provided sessionId is not owned by the authenticated subject: " + providedSessionId);
        }

        return createAndSaveNewSession(subjectId);
    }

    private @NonNull UUID createAndSaveNewSession(@NonNull String subjectId) {
        NltiSession session = new NltiSession();
        UUID sessionId = UUIDv7Generator.generate();
        session.setId(sessionId);
        session.setSubjectId(subjectId);
        sessionRepository.save(session);
        return sessionId;
    }

    private @NonNull String hashPrompt(@NonNull String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    Objects.requireNonNull(prompt, "prompt must not be null").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private @NonNull Cache<UUID, AtomicInteger> sessionRequestCounts() {
        Cache<UUID, AtomicInteger> existing = sessionRequestCountsRef.get();
        if (existing != null) {
            return existing;
        }
        Cache<UUID, AtomicInteger> created = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(sessionTtlHours))
                .maximumSize(maxTrackedSessions)
                .removalListener((UUID sessionId, AtomicInteger value, RemovalCause cause) -> {
                    if (cause.wasEvicted()) {
                        rateLimitCounterEvictions.increment();
                    }
                })
                .build();
        if (sessionRequestCountsRef.compareAndSet(null, created)) {
            return created;
        }
        Cache<UUID, AtomicInteger> installed = sessionRequestCountsRef.get();
        if (installed != null) {
            return installed;
        }
        throw new IllegalStateException("sessionRequestCounts cache was not initialized");
    }
}
