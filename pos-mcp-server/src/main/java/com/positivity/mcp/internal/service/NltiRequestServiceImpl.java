package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiRequest;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.observability.NltiSpanAttributes;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.service.NltiRequestService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.trace.Span;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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

    private final ConcurrentHashMap<String, AtomicInteger> sessionRequestCounts = new ConcurrentHashMap<>();

    public NltiRequestServiceImpl(NltiSessionRepository sessionRepository,
                                   NltiRequestRepository requestRepository,
                                   @NonNull Clock clock,
                                   @NonNull MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.requestRepository = requestRepository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.requestCount = meterRegistry.counter("nlt.request.count");
        this.errorCount = meterRegistry.counter("nlt.error.count");
        this.requestLatency = meterRegistry.timer("nlt.request.latency_ms");
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
            currentSpan.setAttribute(NltiSpanAttributes.NLT_CORRELATION_ID, effectiveCorrelationId.toString());
            currentSpan.setAttribute(NltiSpanAttributes.NLT_USER_ID, subjectId);

            AtomicInteger count = sessionRequestCounts.computeIfAbsent(
                    resolvedSessionId.toString(), k -> new AtomicInteger(0));
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

            currentSpan.setAttribute(NltiSpanAttributes.NLT_REQUEST_ID, newRequestId.toString());
            currentSpan.setAttribute(NltiSpanAttributes.NLT_SESSION_ID, resolvedSessionId.toString());

            return new NltiResponseV1(newRequestId, effectiveCorrelationId, resolvedSessionId, "ACCEPTED", null, null);
        } catch (Exception exception) {
            errorCount.increment();
            throw exception;
        } finally {
            sample.stop(requestLatency);
        }
    }

    private UUID resolveSession(@Nullable UUID providedSessionId, String subjectId) {
        if (providedSessionId == null) {
            return createAndSaveNewSession(subjectId, null);
        }
        return sessionRepository.findByIdAndSubjectId(providedSessionId, subjectId)
                .map(existing -> {
                    boolean expired = existing.getUpdatedAt()
                            .isBefore(OffsetDateTime.now(clock).minusHours(sessionTtlHours));
                    if (expired) {
                        return createAndSaveNewSession(subjectId, null);
                    }
                    return existing.getId();
                })
                .orElseGet(() -> createAndSaveNewSession(subjectId, providedSessionId));
    }

    private UUID createAndSaveNewSession(String subjectId, @Nullable UUID id) {
        NltiSession session = new NltiSession();
        UUID sessionId = (id != null) ? id : UUIDv7Generator.generate();
        session.setId(sessionId);
        session.setSubjectId(subjectId);
        sessionRepository.save(session);
        return sessionId;
    }

    private String hashPrompt(String prompt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(prompt.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
