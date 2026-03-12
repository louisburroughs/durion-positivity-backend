package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.dto.NltiRequestDTO;
import com.positivity.mcp.internal.dto.NltiResponseV1;
import com.positivity.mcp.internal.entity.NltiRequest;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.enums.NltiRequestStatus;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.repository.NltiRequestRepository;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.service.NltiRequestService;
import com.positivity.shared.id.UUIDv7Generator;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class NltiRequestServiceImpl implements NltiRequestService {

    private final NltiSessionRepository sessionRepository;
    private final NltiRequestRepository requestRepository;
    private final Clock clock;

    @Value("${pos.nlti.session.ttl-hours:24}")
    private long sessionTtlHours = 24;

    @Value("${pos.nlti.rate-limit.per-session:100}")
    private int perSessionRateLimit = 100;

    private final ConcurrentHashMap<String, AtomicInteger> sessionRequestCounts = new ConcurrentHashMap<>();

    public NltiRequestServiceImpl(NltiSessionRepository sessionRepository,
                                   NltiRequestRepository requestRepository,
                                   Clock clock) {
        this.sessionRepository = sessionRepository;
        this.requestRepository = requestRepository;
        this.clock = (clock != null) ? clock : Clock.systemUTC();
    }

    @Override
    public @NonNull NltiResponseV1 submit(@NonNull NltiRequestDTO request) {
        return submit(request, null);
    }

    @Override
    public @NonNull NltiResponseV1 submit(@NonNull NltiRequestDTO request, @Nullable UUID correlationId) {
        String subjectId = resolveSubjectId();
        UUID effectiveCorrelationId = (correlationId != null) ? correlationId : UUIDv7Generator.generate();
        UUID resolvedSessionId = resolveSession(request.sessionId(), subjectId);

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

        return new NltiResponseV1(newRequestId, effectiveCorrelationId, resolvedSessionId, "ACCEPTED", null, null);
    }

    private String resolveSubjectId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            return auth.getName();
        }
        return "system";
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
