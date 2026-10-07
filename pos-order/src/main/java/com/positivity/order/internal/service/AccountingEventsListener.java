package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.accounting.PettyExpenseCategoryChangedV1;
import com.positivity.domainevents.accounting.RegisterFloatChangedV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.order.internal.config.FunctionalCurrency;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code accounting.events.v1} into pos-order's copies of accounting's petty-expense
 * categories and register floats (CAP:550 S16, #2512; ADR-0044 R3, §4): {@code
 * accounting.petty-expense-category.changed} into {@code ext_accounting_petty_expense_category} and
 * {@code accounting.float.changed} into {@code ext_accounting_register_float}. pos-order never calls
 * pos-accounting (R1); these facts are the only way the categories and floats arrive.
 *
 * <p>Each copy row is keyed by the fact's aggregate (the accounting row id), and the fact's {@code
 * aggregateVersion} guards it ({@link ReplicaVersionGuard}): a fact older than the row is ignored, so
 * a float fact arriving out of order changes nothing; an equal version applies, which is what lets
 * accounting's start-up republish and a manifest-driven replay repair a row. The float copy keeps the fact's
 * {@code currencyCode} (#2577), the functional currency for a fact without one (ADR-0067 PC-8).
 *
 * <p>Every accounting fact's eventId is recorded in {@code processed_events} (owner {@value #OWNER}),
 * the facts this module ignores included, because accounting's reconciliation manifest counts every
 * fact on the topic ({@link AccountingManifestListener}).
 *
 * <p>Transaction shape (#2146, ADR-0044 §4 amendment 2026-09-23): the listener method is not {@code
 * @Transactional}; the handler and its {@code processed_events} mark run together in a {@code
 * REQUIRES_NEW} transaction of their own. A permanent failure rolls back only that work, is logged, and
 * its eventId is then marked processed in a transaction of its own, so the manifest does not report it
 * as drift forever; transient database errors propagate for container retry and dead-lettering.
 */
@Slf4j
@Component
@KafkaRails
public class AccountingEventsListener {

    static final String OWNER = "accounting";
    static final String LAG_TIMER = "replica.lag";
    static final String FLOAT_LOCATION_MISMATCH = "order.session.float_location_mismatch";

    private static final List<RegisterSessionStatus> ACTIVE_STATUSES =
            List.of(RegisterSessionStatus.OPEN, RegisterSessionStatus.CLOSING);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtAccountingPettyExpenseCategoryRepository categoryRepository;
    private final ExtAccountingRegisterFloatRepository registerFloatRepository;
    private final RegisterSessionRepository registerSessionRepository;
    private final FunctionalCurrency functionalCurrency;
    private final @Nullable MeterRegistry meterRegistry;

    /** The event's handler work and its processed mark, in a transaction of their own. */
    private final TransactionTemplate handlerTransaction;

    public AccountingEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtAccountingPettyExpenseCategoryRepository categoryRepository,
            ExtAccountingRegisterFloatRepository registerFloatRepository,
            RegisterSessionRepository registerSessionRepository,
            FunctionalCurrency functionalCurrency,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.categoryRepository = categoryRepository;
        this.registerFloatRepository = registerFloatRepository;
        this.registerSessionRepository = registerSessionRepository;
        this.functionalCurrency = functionalCurrency;
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.order.kafka.accounting-events-topic:accounting.events.v1}",
            groupId = "${pos.order.kafka.accounting-events-consumer-group:pos-order-accounting-events}")
    public void onAccountingEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable accounting event: {}", message, e);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping accounting event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (PettyExpenseCategoryChangedV1.EVENT_TYPE.equals(eventType)) {
                    applyCategory(envelope);
                } else if (RegisterFloatChangedV1.EVENT_TYPE.equals(eventType)) {
                    applyFloat(envelope);
                }
                processedEventRepository.save(ProcessedEvent.builder()
                        .eventId(eventId)
                        .owner(OWNER)
                        .processedAt(Instant.now(clock))
                        .build());
            });
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then publishes to {topic}.dlq (ADR-0044 §4).
                throw e;
            }
            log.warn("Skipping malformed accounting event eventId={} eventType={}", eventId, eventType, e);
            // Permanent: mark it in a transaction of its own, so accounting's manifest stops reporting the
            // same unrecoverable event as drift on every window (AGENTS.md, ADR-0044 §4).
            recordFailed(eventId);
        }
    }

    private void recordFailed(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build()));
    }

    private void applyCategory(JsonNode envelope) {
        PettyExpenseCategoryChangedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), PettyExpenseCategoryChangedV1.class);
        UUID categoryId = UUID.fromString(envelope.path("aggregateId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);
        ExtAccountingPettyExpenseCategory existing =
                categoryRepository.findById(categoryId).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            return;
        }
        ExtAccountingPettyExpenseCategory copy = existing != null ? existing : new ExtAccountingPettyExpenseCategory();
        copy.setPettyExpenseCategoryId(categoryId);
        copy.setCode(fact.code());
        copy.setLabel(fact.label());
        copy.setExamples(fact.examples());
        copy.setStatus(fact.status().name());
        copy.setAccountCode(fact.accountCode());
        copy.setAccountName(fact.accountName());
        copy.setAggregateVersion(aggregateVersion);
        copy.setSyncedAt(Instant.now(clock));
        categoryRepository.save(copy);
        recordLag(envelope, "petty-expense-category");
    }

    private void applyFloat(JsonNode envelope) {
        RegisterFloatChangedV1 fact = objectMapper.treeToValue(envelope.path("payload"), RegisterFloatChangedV1.class);
        UUID floatId = UUID.fromString(envelope.path("aggregateId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);
        ExtAccountingRegisterFloat existing =
                registerFloatRepository.findById(floatId).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            // Out of order: a session opened since keeps its opening float; the difference is over/short.
            return;
        }
        ExtAccountingRegisterFloat copy = existing != null ? existing : new ExtAccountingRegisterFloat();
        copy.setRegisterFloatId(floatId);
        copy.setRegisterId(fact.registerId());
        copy.setLocationId(fact.locationId());
        // A reversal in accounting can leave a float negative; the copy holds it as it stands.
        copy.setAmount(scale(fact.amount()));
        // ADR-0067 PC-8: a fact without a currency (schema 1 or 2) is in the tenant's functional currency. Until
        // #2583 (ADR-0067 PC-2/A2) that is the interim pos.order.functional-currency, not the tenant's own.
        copy.setCurrencyCode(fact.currencyCode() != null ? fact.currencyCode() : functionalCurrency.code());
        copy.setEffectiveDate(fact.effectiveDate() == null ? LocalDate.now(clock) : fact.effectiveDate());
        copy.setAggregateVersion(aggregateVersion);
        copy.setSyncedAt(Instant.now(clock));
        registerFloatRepository.save(copy);
        recordLag(envelope, "register-float");
        warnOnActiveSessionElsewhere(copy, envelope.path("payload").path("kind").stringValue("UNKNOWN"));
    }

    /**
     * #2573 (Order ruling): no register moves while its drawer is open. The copy follows accounting by
     * state, whatever the kind (a relocation included); an OPEN or CLOSING session of the terminal at
     * another location is left as it is, and the inconsistency is logged and counted for an operator.
     */
    private void warnOnActiveSessionElsewhere(ExtAccountingRegisterFloat copy, String kind) {
        List<RegisterSession> active =
                registerSessionRepository.findByTerminalIdAndStatusIn(copy.getRegisterId(), ACTIVE_STATUSES);
        for (RegisterSession session : active) {
            if (!copy.getLocationId().equals(session.getLocationId())) {
                log.warn(
                        "Register {} float is now at another location than its {} session {}; the session is"
                                + " left unchanged (kind={})",
                        copy.getRegisterId(),
                        session.getStatus(),
                        session.getSessionId(),
                        kind);
                if (meterRegistry != null) {
                    Counter.builder(FLOAT_LOCATION_MISMATCH)
                            .description("Register float facts whose location differs from an open drawer's")
                            .tag("kind", kind)
                            .register(meterRegistry)
                            .increment();
                }
            }
        }
    }

    /** {@code replica.lag}: how long after accounting committed the fact this copy applied it. */
    private void recordLag(JsonNode envelope, String entity) {
        if (meterRegistry == null) {
            return;
        }
        String occurredAt = envelope.path("occurredAtUtc").stringValue(null);
        if (occurredAt == null) {
            return;
        }
        try {
            Duration lag = Duration.between(Instant.parse(occurredAt), Instant.now(clock));
            Timer.builder(LAG_TIMER)
                    .description("Time from the owner's fact to its application in this copy")
                    .tag("owner", OWNER)
                    .tag("entity", entity)
                    .register(meterRegistry)
                    .record(lag.isNegative() ? Duration.ZERO : lag);
        } catch (RuntimeException e) {
            log.debug("Unreadable occurredAtUtc={} on an accounting fact", occurredAt);
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
