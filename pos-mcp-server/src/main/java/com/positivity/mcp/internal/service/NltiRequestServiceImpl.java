package com.positivity.mcp.internal.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiRequest;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.observability.NltiSpanAttributes;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.service.NltiRequestService;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NltiRequestServiceImpl implements NltiRequestService {

    private final NltiSessionRepository sessionRepository;
    private final NltiRequestRepository requestRepository;
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

    public NltiRequestServiceImpl(@NonNull NltiSessionRepository sessionRepository,
                                   @NonNull NltiRequestRepository requestRepository,
                                   @NonNull Clock clock,
                                   @NonNull MeterRegistry meterRegistry) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository must not be null");
        this.requestRepository = Objects.requireNonNull(requestRepository, "requestRepository must not be null");
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
        try {
            requestCount.increment();

            String subjectId = SecurityContextHelper.getCurrentUsernameOrDefault("system");
            UUID effectiveCorrelationId = (correlationId != null) ? correlationId : UUIDv7Generator.generate();
            UUID resolvedSessionId = resolveSession(request.sessionId(), subjectId);

            Span currentSpan = Span.current();
            AttributeKey<String> correlationIdKey = Objects.requireNonNull(
                    NltiSpanAttributes.NLT_CORRELATION_ID,
                    "NLT_CORRELATION_ID must not be null");
            String correlationIdValue = Objects.requireNonNull(
                    effectiveCorrelationId.toString(),
                    "correlationId attribute must not be null");
            AttributeKey<String> userIdKey = Objects.requireNonNull(
                    NltiSpanAttributes.NLT_USER_ID,
                    "NLT_USER_ID must not be null");
            String userIdValue = Objects.requireNonNull(subjectId, "subjectId must not be null");
            currentSpan.setAttribute(correlationIdKey, correlationIdValue);
            currentSpan.setAttribute(userIdKey, userIdValue);

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

            AttributeKey<String> requestIdKey = Objects.requireNonNull(
                    NltiSpanAttributes.NLT_REQUEST_ID,
                    "NLT_REQUEST_ID must not be null");
            String requestIdValue = Objects.requireNonNull(
                    newRequestId.toString(),
                    "requestId attribute must not be null");
            AttributeKey<String> sessionIdKey = Objects.requireNonNull(
                    NltiSpanAttributes.NLT_SESSION_ID,
                    "NLT_SESSION_ID must not be null");
            String sessionIdValue = Objects.requireNonNull(
                    resolvedSessionId.toString(),
                    "sessionId attribute must not be null");
            currentSpan.setAttribute(requestIdKey, requestIdValue);
            currentSpan.setAttribute(sessionIdKey, sessionIdValue);

            return new NltiResponseV1(newRequestId, effectiveCorrelationId, resolvedSessionId, "ACCEPTED", null, null);
        } catch (Exception exception) {
            errorCount.increment();
            throw exception;
        } finally {
            sample.stop(requestLatency);
        }
    }

    private @NonNull UUID resolveSession(@Nullable UUID providedSessionId, @NonNull String subjectId) {
        if (providedSessionId == null) {
            return createAndSaveNewSession(subjectId);
        }
        Optional<NltiSession> sessionForSubject = sessionRepository.findByIdAndSubjectId(providedSessionId, subjectId);
        if (sessionForSubject.isPresent()) {
            NltiSession existing = sessionForSubject.get();
            boolean expired = existing.getUpdatedAt()
                    .isBefore(OffsetDateTime.now(clock).minusHours(sessionTtlHours));
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
            byte[] hash = digest.digest(Objects.requireNonNull(prompt, "prompt must not be null")
                    .getBytes(StandardCharsets.UTF_8));
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
