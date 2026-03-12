package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.dto.ClarificationQuestion;
import com.positivity.mcp.internal.dto.ClarificationResponseDTO;
import com.positivity.mcp.internal.dto.IntentSlot;
import com.positivity.mcp.internal.dto.IntentV1;
import com.positivity.mcp.internal.entity.NltiAuditEvent;
import com.positivity.mcp.internal.entity.NltiIntent;
import com.positivity.mcp.internal.enums.NltiAuditEventType;
import com.positivity.mcp.internal.enums.NltiIntentStatus;
import com.positivity.mcp.internal.enums.NltiIntentType;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import com.positivity.mcp.internal.repository.NltiIntentRepository;
import com.positivity.mcp.service.AuditLedgerService;
import com.positivity.mcp.service.IntentParserService;
import com.positivity.shared.id.UUIDv7Generator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class IntentParserServiceImpl implements IntentParserService {
    private static final Logger log = LoggerFactory.getLogger(IntentParserServiceImpl.class);

    private static final Set<String> HIGH_RISK_VERBS = Set.of("delete", "remove");
    private static final Set<String> QUERY_INDICATORS = Set.of(
            "check", "show", "list", "find", "get", "what", "how many", "price");

    private final NltiIntentRepository repository;
    private final AuditLedgerService auditLedgerService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private Counter parseCounter;
    private Counter clarificationCounter;

    @Autowired
    public IntentParserServiceImpl(
            @NonNull NltiIntentRepository repository,
            @NonNull AuditLedgerService auditLedgerService,
            @NonNull MeterRegistry meterRegistry,
            @NonNull Clock clock,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditLedgerService = auditLedgerService;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    IntentParserServiceImpl(
            @NonNull NltiIntentRepository repository,
            @NonNull AuditLedgerService auditLedgerService,
            @NonNull MeterRegistry meterRegistry,
            @NonNull Clock clock) {
        this(repository, auditLedgerService, meterRegistry, clock, new ObjectMapper());
    }

    @Override
    public @NonNull IntentV1 parse(@NonNull String prompt, @NonNull UUID sessionId, @NonNull UUID correlationId) {
        log.debug("Parsing intent for sessionId={}, correlationId={}", sessionId, correlationId);
        String lower = prompt.toLowerCase(Locale.ROOT);

        NltiRiskLevel risk = classifyRisk(lower);
        NltiIntentType type = classifyType(lower, risk);
        NltiIntentStatus status = classifyStatus(type);
        log.debug("Intent classification result type={}, status={}, risk={}", type, status, risk);

        List<IntentSlot> slots = buildSlots(type, lower);
        List<ClarificationQuestion> questions = buildClarificationQuestions(status);

        NltiIntent intent = new NltiIntent();
        intent.setSessionId(sessionId);
        intent.setCorrelationId(correlationId);
        intent.setIntentType(type);
        intent.setStatus(status);
        intent.setRiskLevel(risk);
        intent.setSlotsJson(toJson(slots));
        intent.setClarificationQuestionsJson(toJson(questions));

        NltiIntent persistedIntent = repository.save(intent);
        if (persistedIntent == null) {
            log.warn("Intent repository returned null for sessionId={}, correlationId={}; using in-memory fallback",
                    sessionId, correlationId);
            persistedIntent = intent;
        }

        appendAuditEvent(NltiAuditEventType.INTENT, correlationId, sessionId);
        incrementParseCounter();
        if (status == NltiIntentStatus.NEEDS_CLARIFICATION) {
            incrementClarificationCounter();
        }

        log.debug("Parse completed intentId={}, type={}, status={}",
                persistedIntent.getId(), persistedIntent.getIntentType(), persistedIntent.getStatus());
        return toIntentV1(persistedIntent, slots, questions);
    }

    @Override
    public @NonNull IntentV1 resolve(@NonNull UUID intentId, @NonNull ClarificationResponseDTO response) {
        boolean hasAnswer = response.userAnswer() != null && !response.userAnswer().isBlank();
        boolean hasSelection = response.selectedOption() != null && !response.selectedOption().isBlank();
        log.debug("Resolving intentId={} hasAnswer={} hasSelectedOption={}", intentId, hasAnswer, hasSelection);
        Optional<NltiIntent> existingIntent = repository.findById(intentId);
        if (existingIntent.isEmpty()) {
            log.warn("Resolve failed; intentId={} not found", intentId);
            throw new NoSuchElementException("Intent not found: " + intentId);
        }
        NltiIntent intent = existingIntent.get();

        String answer = response.userAnswer();
        String selectedOption = response.selectedOption();
        if (answer != null && !answer.isBlank() && selectedOption != null && !selectedOption.isBlank()) {
            intent.setStatus(NltiIntentStatus.READY);
            List<IntentSlot> resolved = List.of(
                    new IntentSlot("selectedOption", selectedOption, 1.0),
                    new IntentSlot("answer", answer, 1.0));
            intent.setSlotsJson(toJson(resolved));
            intent.setClarificationQuestionsJson("[]");
        } else {
            intent.setStatus(NltiIntentStatus.PENDING_CLARIFICATION);
            intent.setClarificationQuestionsJson(
                    toJson(buildClarificationQuestions(NltiIntentStatus.NEEDS_CLARIFICATION)));
        }

        NltiIntent persistedIntent = repository.save(intent);
        appendAuditEvent(NltiAuditEventType.INTENT, persistedIntent.getCorrelationId(), persistedIntent.getSessionId());
        if (persistedIntent.getStatus() != NltiIntentStatus.READY) {
            incrementClarificationCounter();
        }

        List<IntentSlot> slots = fromJson(persistedIntent.getSlotsJson(), new TypeReference<>() {
        });
        List<ClarificationQuestion> questions = fromJson(
                persistedIntent.getClarificationQuestionsJson(),
                new TypeReference<>() {
                });
        log.debug("Resolve completed intentId={} status={}", persistedIntent.getId(), persistedIntent.getStatus());
        return toIntentV1(persistedIntent, slots, questions);
    }

    private NltiRiskLevel classifyRisk(String lower) {
        if (HIGH_RISK_VERBS.stream().anyMatch(lower::contains)) {
            return NltiRiskLevel.HIGH;
        }
        if (lower.startsWith("bulk ")) {
            return NltiRiskLevel.HIGH;
        }
        return NltiRiskLevel.LOW;
    }

    private NltiIntentType classifyType(String lower, NltiRiskLevel risk) {
        if (risk == NltiRiskLevel.HIGH) {
            return NltiIntentType.ACTION;
        }
        if (QUERY_INDICATORS.stream().anyMatch(lower::contains)) {
            return NltiIntentType.QUERY;
        }
        return NltiIntentType.UNKNOWN;
    }

    private NltiIntentStatus classifyStatus(NltiIntentType type) {
        return (type == NltiIntentType.UNKNOWN || type == NltiIntentType.ACTION)
                ? NltiIntentStatus.NEEDS_CLARIFICATION
                : NltiIntentStatus.READY;
    }

    private List<IntentSlot> buildSlots(NltiIntentType type, String lower) {
        if (type == NltiIntentType.QUERY) {
            String subject = extractSubject(lower);
            return List.of(new IntentSlot("subject", subject, 0.9));
        }
        return List.of();
    }

    private String extractSubject(String lower) {
        for (String indicator : QUERY_INDICATORS) {
            int idx = lower.indexOf(indicator);
            if (idx >= 0) {
                String rest = lower.substring(idx + indicator.length()).trim();
                String[] words = rest.split("\\s+");
                if (words.length > 0 && !words[0].isBlank()) {
                    return words[0];
                }
            }
        }
        return "unknown";
    }

    private List<ClarificationQuestion> buildClarificationQuestions(NltiIntentStatus status) {
        if (status == NltiIntentStatus.NEEDS_CLARIFICATION || status == NltiIntentStatus.PENDING_CLARIFICATION) {
            return List.of(new ClarificationQuestion(
                    "What would you like to do?",
                    List.of("Check inventory", "Create an order", "View workorders", "Other")));
        }
        return List.of();
    }

    private void appendAuditEvent(NltiAuditEventType eventType, UUID correlationId, UUID sessionId) {
        NltiAuditEvent event = new NltiAuditEvent();
        event.setId(UUIDv7Generator.generate());
        event.setCorrelationId(correlationId);
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setTimestamp(resolveNow());
        log.debug("Appending audit event eventId={} eventType={} correlationId={} sessionId={}",
                event.getId(), eventType, correlationId, sessionId);
        auditLedgerService.append(event);
    }

    private OffsetDateTime resolveNow() {
        return OffsetDateTime.now(clock);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            String typeName = (obj != null) ? obj.getClass().getSimpleName() : "null";
            log.warn("JSON serialization failed for type={}, returning []: {}", typeName, e.getMessage());
            return "[]";
        }
    }

    private void incrementParseCounter() {
        if (parseCounter == null) {
            parseCounter = meterRegistry.counter("nlt.intent.parse.count");
        }
        parseCounter.increment();
    }

    private void incrementClarificationCounter() {
        if (clarificationCounter == null) {
            clarificationCounter = meterRegistry.counter("nlt.intent.clarification.count");
        }
        clarificationCounter.increment();
    }

    private <T> List<T> fromJson(String json, TypeReference<List<T>> ref) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, ref);
        } catch (JsonProcessingException e) {
            int jsonLength = json.length();
            log.warn("JSON deserialization failed for targetType={} (jsonLength={}), returning empty list: {}",
                    ref.getType(), jsonLength, e.getMessage());
            return List.of();
        }
    }

    private IntentV1 toIntentV1(NltiIntent intent, List<IntentSlot> slots, List<ClarificationQuestion> questions) {
        return new IntentV1(
                intent.getId(),
                intent.getIntentType() != null ? intent.getIntentType().name() : null,
                intent.getStatus() != null ? intent.getStatus().name() : null,
                intent.getRiskLevel() != null ? intent.getRiskLevel().name() : null,
                slots,
                questions);
    }
}
