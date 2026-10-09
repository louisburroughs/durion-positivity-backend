package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSetting;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.accounting.PettyExpenseCategoryChangedV1;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Queues {@code accounting.petty-expense-category.changed} (#2511; ADR-0044 §3) on {@code
 * accounting.events.v1} through the outbox, in the caller's transaction: after every category
 * command, when the tenant template creates a category, and at each start for every category.
 *
 * <p>The account is the one the category's mapping key posts to today: the undimensioned GL mapping
 * effective on today's date in the tenant's accounting zone. A tenant whose zone is not set yet (a
 * tenant being provisioned) is told the open-ended mapping instead; nothing is dated from the
 * system zone (#2558).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PettyExpenseCategoryFacts {

    static final String SOURCE_SERVICE = "pos-accounting";
    static final String ACCOUNTING_EVENTS_TOPIC = DomainTopics.events("accounting");

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final GLMappingRepository glMappings;
    private final PettyExpenseCategoryTaxSettingRepository taxSettings;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final Clock clock;

    /** The fact a category's current state makes. */
    public @NonNull PettyExpenseCategoryChangedV1 factOf(@NonNull PettyExpenseCategory category) {
        Optional<GLAccount> account = currentAccount(category);
        // CAP:550 S32d item 4: the category's tax recovery; a category never set is not recoverable.
        Optional<PettyExpenseCategoryTaxSetting> taxSetting = taxSettings.findByCode(category.getCode());
        return new PettyExpenseCategoryChangedV1(
                category.getCode(),
                category.getLabel(),
                category.getExamples(),
                PettyExpenseCategoryChangedV1.Status.valueOf(
                        category.getStatus().name()),
                account.map(GLAccount::getAccountCode).orElse(null),
                account.map(GLAccount::getAccountName).orElse(null),
                taxSetting.map(PettyExpenseCategoryTaxSetting::isTaxRecoverable).orElse(Boolean.FALSE),
                taxSetting
                        .map(PettyExpenseCategoryTaxSetting::getRecoverablePercent)
                        .orElse(null));
    }

    /** Queues the category's current state; must run inside the transaction that changed it. */
    public void changed(@NonNull PettyExpenseCategory category, @NonNull String actor) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        PettyExpenseCategoryChangedV1 payload = factOf(category);
        writer.publish(
                ACCOUNTING_EVENTS_TOPIC,
                DomainEventEnvelope.of(
                        PettyExpenseCategoryChangedV1.EVENT_TYPE,
                        PettyExpenseCategoryChangedV1.SCHEMA_VERSION,
                        category.getPettyExpenseCategoryId(),
                        category.getVersion(),
                        SOURCE_SERVICE,
                        null,
                        actor,
                        payload,
                        clock));
        log.debug("Queued {} code={}", PettyExpenseCategoryChangedV1.EVENT_TYPE, category.getCode());
    }

    private Optional<GLAccount> currentAccount(PettyExpenseCategory category) {
        List<GLMapping> mappings = glMappings.findByMappingKey_MappingKeyId(category.getMappingKeyId()).stream()
                .filter(m -> m.getDimensions() == null || m.getDimensions().isEmpty())
                .toList();
        Optional<LocalDateTime> today = zoneResolver
                .find()
                .map(zone -> clock.instant().atZone(zone).toLocalDate().atStartOfDay());
        Optional<GLMapping> current = today.isPresent()
                ? mappings.stream().filter(m -> m.isEffectiveOn(today.get())).findFirst()
                : mappings.stream()
                        .filter(m -> m.getEffectiveEndDate() == null)
                        .max(Comparator.comparing(GLMapping::getEffectiveStartDate));
        return current.map(GLMapping::getGlAccount);
    }
}
