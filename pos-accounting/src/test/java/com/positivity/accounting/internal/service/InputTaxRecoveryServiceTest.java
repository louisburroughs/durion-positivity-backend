package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.TaxProfileClient;
import com.positivity.accounting.internal.dto.InputTaxRecoveryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryTaxRecoveryRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryTaxRecoveryResponse;
import com.positivity.accounting.internal.entity.ExtTaxRegistration;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSetting;
import com.positivity.accounting.internal.entity.PettyExpenseCategoryTaxSettingChange;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingChangeRepository;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryTaxSettingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** CAP:550 S32d items 2 and 4: the settings read and a category's tax recovery (AC 14). Fixture data only. */
@DisplayName("S32d input-tax recovery settings")
class InputTaxRecoveryServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T14:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.parse("2026-10-20");
    private static final UUID REQUEST = UUID.fromString("019a0000-0000-7000-8000-000000000107");

    private final InputTaxRecoveryFlags flags = mock(InputTaxRecoveryFlags.class);
    private final TaxProfileClient taxProfiles = mock(TaxProfileClient.class);
    private final PettyExpenseCategoryRepository categories = mock(PettyExpenseCategoryRepository.class);
    private final PettyExpenseCategoryTaxSettingRepository settings =
            mock(PettyExpenseCategoryTaxSettingRepository.class);
    private final PettyExpenseCategoryTaxSettingChangeRepository changes =
            mock(PettyExpenseCategoryTaxSettingChangeRepository.class);
    private final GLMappingResolver resolver = mock(GLMappingResolver.class);
    private final GLAccountRepository accounts = mock(GLAccountRepository.class);
    private final PettyExpenseCategoryFacts facts = mock(PettyExpenseCategoryFacts.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private InputTaxRecoveryService service;
    private PettyExpenseCategory meals;

    @BeforeEach
    void setUp() {
        service = new InputTaxRecoveryService(
                flags,
                taxProfiles,
                categories,
                settings,
                changes,
                resolver,
                accounts,
                facts,
                TestZoneResolvers.utc(CLOCK),
                entityManager,
                mapper,
                CLOCK);
        meals = new PettyExpenseCategory();
        meals.setPettyExpenseCategoryId(UUID.randomUUID());
        meals.setCode("STAFF_MEALS");
        meals.setLabel("Staff meals");
        when(categories.findByCode("STAFF_MEALS")).thenReturn(Optional.of(meals));
        when(categories.findAllByOrderByCodeAsc()).thenReturn(List.of(meals));
        when(settings.saveAndFlush(any())).thenAnswer(call -> {
            PettyExpenseCategoryTaxSetting saved = call.getArgument(0);
            saved.setVersion(saved.getVersion() == null ? 0 : saved.getVersion() + 1);
            return saved;
        });
    }

    private static PettyExpenseCategoryTaxRecoveryRequest request(
            Boolean recoverable, String percent, String justification, Integer version) {
        return new PettyExpenseCategoryTaxRecoveryRequest(
                recoverable, percent == null ? null : new BigDecimal(percent), justification, REQUEST, version);
    }

    @ParameterizedTest(name = "{0}/{1}/{2}/{3} -> 400")
    @CsvSource(
            value = {
                "true,50.00,short,0",
                "NULL,50.00,Meals are half recoverable,0",
                "true,NULL,Meals are half recoverable,0",
                "true,0,Meals are half recoverable,0",
                "true,100.01,Meals are half recoverable,0",
                "true,50.001,Meals are half recoverable,0",
                "false,50.00,Meals are half recoverable,0",
                "true,50.00,Meals are half recoverable,NULL"
            },
            nullValues = "NULL")
    @DisplayName("AC 14: an invalid body, a justification under 10 characters included, is 400")
    void invalidBody(Boolean recoverable, String percent, String justification, Integer version) {
        assertThatThrownBy(() -> service.setCategoryRecovery(
                        "STAFF_MEALS", request(recoverable, percent, justification, version)))
                .isInstanceOf(InvalidRequestParameterException.class);
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AC 14: a tenant with no regime enabled is 422 INPUT_TAX_RECOVERY_NOT_ENABLED")
    void notEnabled() {
        when(flags.anyEnabled(TODAY)).thenReturn(false);

        assertThatThrownBy(() -> service.setCategoryRecovery(
                        "STAFF_MEALS", request(true, "50.00", "Meals are half recoverable", 0)))
                .isInstanceOfSatisfying(
                        CashSetupException.class,
                        e -> assertThat(e.getCode()).isEqualTo(CashSetupException.Code.INPUT_TAX_RECOVERY_NOT_ENABLED));
        verify(settings, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AC 14: a change writes the setting, a history row from now, and the category fact")
    void changeWritesSettingHistoryAndFact() {
        when(flags.anyEnabled(TODAY)).thenReturn(true);

        PettyExpenseCategoryTaxRecoveryResponse response =
                service.setCategoryRecovery("STAFF_MEALS", request(true, "50.00", "Meals are half recoverable", 0));

        assertThat(response.taxRecoverable()).isTrue();
        assertThat(response.recoverablePercent()).isEqualByComparingTo("50.00");
        assertThat(response.replayed()).isFalse();
        ArgumentCaptor<PettyExpenseCategoryTaxSettingChange> change =
                ArgumentCaptor.forClass(PettyExpenseCategoryTaxSettingChange.class);
        verify(changes).saveAndFlush(change.capture());
        assertThat(change.getValue().getEffectiveFrom()).isEqualTo(CLOCK.instant());
        assertThat(change.getValue().getOldTaxRecoverable()).isNull();
        assertThat(change.getValue().getNewRecoverablePercent()).isEqualByComparingTo("50.00");
        assertThat(change.getValue().getJustification()).isEqualTo("Meals are half recoverable");
        verify(entityManager).lock(meals, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        verify(facts).changed(eq(meals), anyString());
    }

    @Test
    @DisplayName("AC 14: the same requestId twice makes one change")
    void replayMakesOneChange() {
        when(flags.anyEnabled(TODAY)).thenReturn(true);
        PettyExpenseCategoryTaxRecoveryRequest body = request(true, "50.00", "Meals are half recoverable", 0);
        PettyExpenseCategoryTaxRecoveryResponse first = service.setCategoryRecovery("STAFF_MEALS", body);
        ArgumentCaptor<PettyExpenseCategoryTaxSettingChange> change =
                ArgumentCaptor.forClass(PettyExpenseCategoryTaxSettingChange.class);
        verify(changes).saveAndFlush(change.capture());
        when(changes.findByRequestId(REQUEST)).thenReturn(Optional.of(change.getValue()));

        PettyExpenseCategoryTaxRecoveryResponse second = service.setCategoryRecovery("STAFF_MEALS", body);

        assertThat(second.replayed()).isTrue();
        assertThat(second.recoverablePercent()).isEqualByComparingTo(first.recoverablePercent());
        verify(changes, times(1)).saveAndFlush(any());
        verify(facts, times(1)).changed(any(), anyString());

        assertThatThrownBy(() -> service.setCategoryRecovery(
                        "STAFF_MEALS", request(true, "60.00", "Meals are half recoverable", 0)))
                .isInstanceOfSatisfying(
                        CashSetupException.class,
                        e -> assertThat(e.getCode()).isEqualTo(CashSetupException.Code.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("AC 14: a stale version is 409 OPTIMISTIC_LOCK")
    void staleVersion() {
        when(flags.anyEnabled(TODAY)).thenReturn(true);
        PettyExpenseCategoryTaxSetting current = new PettyExpenseCategoryTaxSetting();
        current.setCode("STAFF_MEALS");
        current.setVersion(3);
        when(settings.findByCode("STAFF_MEALS")).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.setCategoryRecovery(
                        "STAFF_MEALS", request(true, "50.00", "Meals are half recoverable", 2)))
                .isInstanceOfSatisfying(
                        CashSetupException.class,
                        e -> assertThat(e.getCode()).isEqualTo(CashSetupException.Code.OPTIMISTIC_LOCK));
    }

    @Test
    @DisplayName("AC 14: an unknown category is 404")
    void unknownCategory() {
        assertThatThrownBy(() ->
                        service.setCategoryRecovery("NOPE", request(true, "50.00", "Meals are half recoverable", 0)))
                .isInstanceOfSatisfying(
                        CashSetupException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(CashSetupException.Code.PETTY_EXPENSE_CATEGORY_NOT_FOUND));
    }

    @Test
    @DisplayName("AC 1: a tenant without a registration reads no regimes, and its categories not recoverable")
    void usdTenantReadsNoRegimes() {
        when(flags.regimes(TODAY)).thenReturn(List.of());

        InputTaxRecoveryResponse read = service.read();

        assertThat(read.regimes()).isEmpty();
        assertThat(read.evidenceRules()).isEmpty();
        assertThat(read.categories()).singleElement().satisfies(category -> {
            assertThat(category.taxRecoverable()).isFalse();
            assertThat(category.version()).isZero();
        });
    }

    @Test
    @DisplayName("AC 2 / item 2: a registered regime reads enabled, with its registration and account")
    void registeredRegimeReads() {
        ExtTaxRegistration registration = ExtTaxRegistration.builder()
                .countryCode("CA")
                .regime("GST_HST")
                .registrationNumber("123456789RT0001")
                .effectiveFrom(LocalDate.parse("2026-01-01"))
                .build();
        when(flags.regimes(TODAY))
                .thenReturn(List.of(new InputTaxRecoveryFlags.RegimeFlag("CA", "GST_HST", true, registration)));
        UUID accountId = UUID.randomUUID();
        when(resolver.resolveGLAccount(eq("REGISTER_CASH_MOVEMENT"), eq("INPUT_TAX_GST_HST"), any()))
                .thenReturn(accountId);
        GLAccount account = new GLAccount(accountId);
        account.setAccountCode("1250");
        account.setAccountName("GST/HST Recoverable");
        when(accounts.findById(accountId)).thenReturn(Optional.of(account));
        when(taxProfiles.evidenceRules("CA", TODAY))
                .thenReturn(new TaxProfileClient.EvidenceRules("CA", TODAY, "CAD", List.of(), null));

        InputTaxRecoveryResponse read = service.read();

        assertThat(read.regimes()).singleElement().satisfies(regime -> {
            assertThat(regime.enabled()).isTrue();
            assertThat(regime.registration().since()).isEqualTo(LocalDate.parse("2026-01-01"));
            assertThat(regime.account().code()).isEqualTo("1250");
        });
        assertThat(read.evidenceRules())
                .singleElement()
                .satisfies(rules -> assertThat(rules.currencyCode()).isEqualTo("CAD"));
    }

    @Test
    @DisplayName("item 2: the read never fails because of pos-tax: enabled and evidenceRules are null")
    void readSurvivesTaxServiceDown() {
        ExtTaxRegistration registration = ExtTaxRegistration.builder()
                .countryCode("CA")
                .regime("GST_HST")
                .registrationNumber("123456789RT0001")
                .effectiveFrom(LocalDate.parse("2026-01-01"))
                .build();
        when(flags.regimes(TODAY)).thenThrow(new TaxServiceUnavailableException("down"));
        when(flags.regimesUnchecked(TODAY))
                .thenReturn(List.of(new InputTaxRecoveryFlags.RegimeFlag("CA", "GST_HST", false, registration)));
        when(resolver.resolveGLAccount(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("unmapped"));
        when(taxProfiles.evidenceRules("CA", TODAY)).thenThrow(new TaxServiceUnavailableException("down"));

        InputTaxRecoveryResponse read = service.read();

        assertThat(read.regimes()).singleElement().satisfies(regime -> {
            assertThat(regime.enabled()).isNull();
            assertThat(regime.account()).isNull();
        });
        assertThat(read.evidenceRules()).isNull();
    }

    @Test
    @DisplayName("AW52: the share in force is the latest change at or before the instant, if recoverable")
    void shareInForce() {
        Instant at = Instant.parse("2026-10-20T15:00:00Z");
        PettyExpenseCategoryTaxSettingChange half = new PettyExpenseCategoryTaxSettingChange();
        half.setNewTaxRecoverable(true);
        half.setNewRecoverablePercent(new BigDecimal("50.00"));
        when(changes.findFirstByCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDescCreatedAtDesc(
                        "STAFF_MEALS", at))
                .thenReturn(Optional.of(half));
        PettyExpenseCategoryTaxSettingChange off = new PettyExpenseCategoryTaxSettingChange();
        off.setNewTaxRecoverable(false);
        when(changes.findFirstByCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDescCreatedAtDesc(
                        "SMALL_TOOLS", at))
                .thenReturn(Optional.of(off));

        assertThat(service.shareInForce("STAFF_MEALS", at)).contains(new BigDecimal("50.00"));
        assertThat(service.shareInForce("SMALL_TOOLS", at)).isEmpty();
        assertThat(service.shareInForce("OFFICE_SUPPLIES", at)).isEmpty();
    }
}
