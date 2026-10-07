package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.PettyExpenseCategoryStatus;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.RegisterFloatChangeRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.accounting.PettyExpenseCategoryChangedV1;
import com.positivity.domainevents.accounting.RegisterFloatChangedV1;
import com.positivity.tenancy.TenantIterator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

@DisplayName("Petty-expense category and register float facts (#2511 AC 8, AC 13)")
class CashSetupFactsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final GLMappingRepository glMappings = mock(GLMappingRepository.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final PettyExpenseCategoryRepository categories = mock(PettyExpenseCategoryRepository.class);
    private final RegisterFloatRepository floats = mock(RegisterFloatRepository.class);
    private final RegisterFloatChangeRepository floatChanges = mock(RegisterFloatChangeRepository.class);
    private PettyExpenseCategoryFacts categoryFacts;
    private RegisterFloatFacts floatFacts;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OutboxEventWriter> writers = mock(ObjectProvider.class);
        when(writers.getIfAvailable()).thenReturn(writer);
        categoryFacts = new PettyExpenseCategoryFacts(writers, glMappings, zoneResolver, CLOCK);
        floatFacts = new RegisterFloatFacts(writers, CLOCK, mock(ObjectProvider.class));
        when(zoneResolver.find()).thenReturn(Optional.of(ZoneOffset.UTC));
    }

    @Test
    @DisplayName("AC8: the category fact carries the current label, status and the account it posts to today")
    void categoryFactCarriesTheCurrentState() {
        PettyExpenseCategory category = category("STAFF_MEALS", "Staff meals and coffee");
        GLAccount meals = new GLAccount(UUID.randomUUID());
        meals.setAccountCode("6295");
        meals.setAccountName("Staff Meals & Refreshments");
        GLMapping mapping = new GLMapping();
        mapping.setGlAccount(meals);
        mapping.setEffectiveStartDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        when(glMappings.findByMappingKey_MappingKeyId(category.getMappingKeyId()))
                .thenReturn(List.of(mapping));

        categoryFacts.changed(category, "controller.cfo");

        PettyExpenseCategoryChangedV1 fact =
                published(PettyExpenseCategoryChangedV1.class, 1).get(0);
        assertThat(fact)
                .isEqualTo(new PettyExpenseCategoryChangedV1(
                        "STAFF_MEALS",
                        "Staff meals and coffee",
                        null,
                        PettyExpenseCategoryChangedV1.Status.ACTIVE,
                        "6295",
                        "Staff Meals & Refreshments"));
    }

    @Test
    @DisplayName("AC13: a start republishes every category and float of the bound tenant once")
    void bootstrapRepublishesTheCurrentState() {
        when(categories.findAllByOrderByCodeAsc())
                .thenReturn(
                        List.of(category("SHOP_SUPPLIES", "Shop supplies"), category("STAFF_MEALS", "Staff meals")));
        RegisterFloat registerFloat = new RegisterFloat();
        registerFloat.setRegisterFloatId(UUID.randomUUID());
        registerFloat.setRegisterId("T-1");
        registerFloat.setLocationId(UUID.randomUUID());
        registerFloat.setAmount(new BigDecimal("150.00"));
        registerFloat.setCurrencyCode("USD");
        RegisterFloatChange latest = new RegisterFloatChange();
        latest.setKind(RegisterFloatChangeKind.CHANGE);
        latest.setEffectiveDate(LocalDate.of(2026, 10, 2));
        latest.setJournalEntryId(UUID.randomUUID());
        when(floats.findAllByOrderByRegisterIdAsc()).thenReturn(List.of(registerFloat));
        when(floatChanges.findFirstByRegisterFloatIdOrderByCreatedAtDescChangeIdDesc(
                        registerFloat.getRegisterFloatId()))
                .thenReturn(Optional.of(latest));
        CashSetupFactsBootstrap bootstrap = new CashSetupFactsBootstrap(
                mock(TenantIterator.class),
                categories,
                floats,
                floatChanges,
                categoryFacts,
                floatFacts,
                mock(PlatformTransactionManager.class));

        assertThat(bootstrap.republishBoundTenant()).isEqualTo(3);

        List<DomainEventEnvelope<?>> envelopes = envelopes(3);
        assertThat(envelopes)
                .extracting(DomainEventEnvelope::eventType)
                .containsExactly(
                        PettyExpenseCategoryChangedV1.EVENT_TYPE,
                        PettyExpenseCategoryChangedV1.EVENT_TYPE,
                        RegisterFloatChangedV1.EVENT_TYPE);
        RegisterFloatChangedV1 fact = (RegisterFloatChangedV1) envelopes.get(2).payload();
        assertThat(fact.amount()).isEqualByComparingTo("150.00");
        assertThat(fact.previousAmount()).as("a republish changes nothing").isEqualByComparingTo("150.00");
        // #2577 (ADR-0067 R-1): the republish states the float's currency, at schema version 3.
        assertThat(fact.currencyCode()).isEqualTo("USD");
        assertThat(envelopes.get(2).schemaVersion()).isEqualTo(3);
        assertThat(envelopes.get(2).aggregateId()).isEqualTo(registerFloat.getRegisterFloatId());
    }

    private static PettyExpenseCategory category(String code, String label) {
        PettyExpenseCategory category = new PettyExpenseCategory();
        category.setPettyExpenseCategoryId(UUID.randomUUID());
        category.setMappingKeyId(UUID.randomUUID());
        category.setCode(code);
        category.setLabel(label);
        category.setStatus(PettyExpenseCategoryStatus.ACTIVE);
        return category;
    }

    @SuppressWarnings("unchecked")
    private List<DomainEventEnvelope<?>> envelopes(int count) {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, times(count)).publish(eq("accounting.events.v1"), captor.capture());
        return captor.getAllValues();
    }

    private <T> List<T> published(Class<T> type, int count) {
        return envelopes(count).stream().map(e -> type.cast(e.payload())).toList();
    }
}
