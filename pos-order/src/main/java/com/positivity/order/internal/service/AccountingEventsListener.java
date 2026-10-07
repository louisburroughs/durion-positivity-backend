package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.accounting.PettyExpenseCategoryChangedV1;
import com.positivity.domainevents.accounting.RegisterFloatChangedV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.order.internal.entity.ExtAccountingPettyExpenseCategory;
import com.positivity.order.internal.entity.ExtAccountingRegisterFloat;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtAccountingPettyExpenseCategoryRepository;
import com.positivity.order.internal.repository.ExtAccountingRegisterFloatRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * accounting's start-up republish and a manifest-driven replay repair a row.
 *
 * <p>Every accounting fact's eventId is recorded in {@code processed_events} (owner {@value #OWNER}),
 * the facts this module ignores included, because accounting's reconciliation manifest counts every
 * fact on the topic ({@link AccountingManifestListener}).
 *
 * <p>Transaction shape (#2146, ADR-0044 §4 amendment 2026-09-23): the listener method is not {@code
 * @Transactional}; the handler and its {@code processed_events} mark run together in a {@code
 * REQUIRES_NEW} transaction of their own. A permanent failure rolls back only that work and is logged
 * and skipped; transient database errors propagate for container retry and dead-lettering.
 */
@Slf4j
@Component
@KafkaRails
public class AccountingEventsListener {

    static final String OWNER = "accounting";
    static final String LAG_TIMER = "replica.lag";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtAccountingPettyExpenseCategoryRepository categoryRepository;
    private final ExtAccountingRegisterFloatRepository registerFloatRepository;
    private final @Nullable MeterRegistry meterRegistry;

    /** The event's handler work and its processed mark, in a transaction of their own. */
    private final TransactionTemplate handlerTransaction;

    public AccountingEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtAccountingPettyExpenseCategoryRepository categoryRepository,
            ExtAccountingRegisterFloatRepository registerFloatRepository,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.categoryRepository = categoryRepository;
        this.registerFloatRepository = registerFloatRepository;
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
        }
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
        copy.setEffectiveDate(fact.effectiveDate() == null ? LocalDate.now(clock) : fact.effectiveDate());
        copy.setAggregateVersion(aggregateVersion);
        copy.setSyncedAt(Instant.now(clock));
        registerFloatRepository.save(copy);
        recordLag(envelope, "register-float");
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
