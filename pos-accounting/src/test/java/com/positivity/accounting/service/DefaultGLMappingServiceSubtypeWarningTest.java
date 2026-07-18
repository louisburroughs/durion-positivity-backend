package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.DefaultGLMappingRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.service.DefaultGLMappingServiceImpl;
import com.positivity.accounting.internal.service.GLAccountServiceImpl;
import com.positivity.accounting.internal.service.GLMappingSubtypeValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the Story H1 subtype plausibility warning seam in
 * {@link DefaultGLMappingServiceImpl#createDefaultMapping} and
 * {@link DefaultGLMappingServiceImpl#updateDefaultMapping} (PR #970 review F3),
 * mirroring {@link GLMappingServiceSubtypeWarningTest}.
 *
 * The check is log-only and strictly non-blocking: a default mapping whose
 * cash-receipt/settlement event type resolves to an implausibly-subtyped
 * debit or credit account is still created/updated; plausible or absent
 * subtypes produce no warning.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultGLMappingService Subtype Warning Tests")
class DefaultGLMappingServiceSubtypeWarningTest {

    private static final UUID MAPPING_ID = UUID.fromString("00000000-0000-4000-a000-000000000010");
    private static final UUID DEBIT_ACCOUNT_ID = UUID.fromString("00000000-0000-4000-a000-000000000011");
    private static final UUID CREDIT_ACCOUNT_ID = UUID.fromString("00000000-0000-4000-a000-000000000012");
    private static final String CASH_RECEIPT_EVENT = "PAYMENT_APPLICATION_DEFAULT";
    private static final Instant FIXED_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

    @Spy
    private Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    @Mock
    private DefaultGLMappingRepository repository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Mock
    private GLAccountServiceImpl glAccountService;

    @Spy
    private GLMappingSubtypeValidator subtypeValidator = new GLMappingSubtypeValidator();

    @InjectMocks
    private DefaultGLMappingServiceImpl service;

    private GLAccount implausibleRevenueAccount;
    private GLAccount plausibleCashAccount;

    @BeforeEach
    void setUp() {
        implausibleRevenueAccount = new GLAccount();
        implausibleRevenueAccount.setGlAccountId(DEBIT_ACCOUNT_ID);
        implausibleRevenueAccount.setAccountCode("4000");
        implausibleRevenueAccount.setAccountName("Service Revenue");
        implausibleRevenueAccount.setAccountType(AccountType.REVENUE);
        implausibleRevenueAccount.setAccountSubtype(AccountSubtype.SALES);
        implausibleRevenueAccount.setActivationDate(LocalDateTime.of(2025, 1, 1, 0, 0));

        plausibleCashAccount = new GLAccount();
        plausibleCashAccount.setGlAccountId(CREDIT_ACCOUNT_ID);
        plausibleCashAccount.setAccountCode("1000");
        plausibleCashAccount.setAccountName("Cash");
        plausibleCashAccount.setAccountType(AccountType.ASSET);
        plausibleCashAccount.setAccountSubtype(AccountSubtype.BANK_CASH);
        plausibleCashAccount.setActivationDate(LocalDateTime.of(2025, 1, 1, 0, 0));
    }

    private DefaultGLMappingRequest request(String eventType) {
        return DefaultGLMappingRequest.builder()
                .eventType(eventType)
                .debitAccountId(DEBIT_ACCOUNT_ID)
                .creditAccountId(CREDIT_ACCOUNT_ID)
                .description("Subtype warning seam test")
                .active(true)
                .build();
    }

    private DefaultGLMapping persistedMapping(String eventType) {
        DefaultGLMapping mapping = new DefaultGLMapping();
        mapping.setMappingId(MAPPING_ID);
        mapping.setEventType(eventType);
        mapping.setDebitAccountId(DEBIT_ACCOUNT_ID);
        mapping.setCreditAccountId(CREDIT_ACCOUNT_ID);
        mapping.setActive(true);
        mapping.setCreatedAt(FIXED_INSTANT);
        mapping.setCreatedBy("test-user");
        mapping.setUpdatedAt(FIXED_INSTANT);
        mapping.setModifiedBy("test-user");
        return mapping;
    }

    private void stubHappyPath(String eventType) {
        doNothing().when(glAccountService).validateAccountForPosting(any(), any());
        when(glAccountRepository.findById(DEBIT_ACCOUNT_ID)).thenReturn(Optional.of(implausibleRevenueAccount));
        when(glAccountRepository.findById(CREDIT_ACCOUNT_ID)).thenReturn(Optional.of(plausibleCashAccount));
        when(glAccountRepository.getReferenceById(DEBIT_ACCOUNT_ID)).thenReturn(implausibleRevenueAccount);
        when(glAccountRepository.getReferenceById(CREDIT_ACCOUNT_ID)).thenReturn(plausibleCashAccount);
        when(repository.save(any(DefaultGLMapping.class))).thenReturn(persistedMapping(eventType));
    }

    @Test
    @DisplayName("createDefaultMapping runs the check and still creates despite implausible debit subtype")
    void createDespiteImplausibleSubtype() {
        stubHappyPath(CASH_RECEIPT_EVENT);

        DefaultGLMappingResponse response = service.createDefaultMapping(request(CASH_RECEIPT_EVENT));

        // Non-blocking: mapping created despite the warning.
        assertThat(response).isNotNull();
        assertThat(response.getMappingId()).isEqualTo(MAPPING_ID);
        assertThat(response.getEventType()).isEqualTo(CASH_RECEIPT_EVENT);
        verify(repository).save(any(DefaultGLMapping.class));

        // Both resolved accounts pass through the validator seam.
        verify(subtypeValidator).checkCashReceiptSubtype(CASH_RECEIPT_EVENT, implausibleRevenueAccount);
        verify(subtypeValidator).checkCashReceiptSubtype(CASH_RECEIPT_EVENT, plausibleCashAccount);

        // Warning fires for the implausible SALES debit account, stays silent for BANK_CASH.
        assertThat(subtypeValidator.checkCashReceiptSubtype(CASH_RECEIPT_EVENT, implausibleRevenueAccount))
                .hasValueSatisfying(warning -> assertThat(warning)
                        .contains(CASH_RECEIPT_EVENT)
                        .contains("4000")
                        .contains("SALES")
                        .contains("warning only"));
        assertThat(subtypeValidator.checkCashReceiptSubtype(CASH_RECEIPT_EVENT, plausibleCashAccount))
                .isEmpty();
    }

    @Test
    @DisplayName("updateDefaultMapping runs the check and still updates despite implausible debit subtype")
    void updateDespiteImplausibleSubtype() {
        when(repository.findById(MAPPING_ID)).thenReturn(Optional.of(persistedMapping("INVOICE_CREATED")));
        stubHappyPath(CASH_RECEIPT_EVENT);

        DefaultGLMappingResponse response = service.updateDefaultMapping(MAPPING_ID, request(CASH_RECEIPT_EVENT));

        // Non-blocking: mapping updated despite the warning.
        assertThat(response).isNotNull();
        assertThat(response.getEventType()).isEqualTo(CASH_RECEIPT_EVENT);
        verify(repository).save(any(DefaultGLMapping.class));

        // Both resolved accounts pass through the validator seam.
        verify(subtypeValidator).checkCashReceiptSubtype(CASH_RECEIPT_EVENT, implausibleRevenueAccount);
        verify(subtypeValidator).checkCashReceiptSubtype(CASH_RECEIPT_EVENT, plausibleCashAccount);
        assertThat(subtypeValidator.checkCashReceiptSubtype(CASH_RECEIPT_EVENT, implausibleRevenueAccount))
                .isPresent();
    }

    @Test
    @DisplayName("createDefaultMapping stays silent for non-cash-receipt event types")
    void createSilentForUnrelatedEventType() {
        stubHappyPath("INVOICE_CREATED");

        DefaultGLMappingResponse response = service.createDefaultMapping(request("INVOICE_CREATED"));

        assertThat(response).isNotNull();
        verify(repository).save(any(DefaultGLMapping.class));
        verify(subtypeValidator).checkCashReceiptSubtype("INVOICE_CREATED", implausibleRevenueAccount);
        verify(subtypeValidator).checkCashReceiptSubtype("INVOICE_CREATED", plausibleCashAccount);
        assertThat(subtypeValidator.checkCashReceiptSubtype("INVOICE_CREATED", implausibleRevenueAccount))
                .isEmpty();
    }

    @Test
    @DisplayName("createDefaultMapping stays silent when the account has no subtype")
    void createSilentForNullSubtype() {
        implausibleRevenueAccount.setAccountSubtype(null);
        plausibleCashAccount.setAccountSubtype(null);
        stubHappyPath(CASH_RECEIPT_EVENT);

        DefaultGLMappingResponse response = service.createDefaultMapping(request(CASH_RECEIPT_EVENT));

        assertThat(response).isNotNull();
        verify(repository).save(any(DefaultGLMapping.class));
        assertThat(subtypeValidator.checkCashReceiptSubtype(CASH_RECEIPT_EVENT, implausibleRevenueAccount))
                .as("no warning for a cash-receipt code when subtype is absent")
                .isEmpty();
    }
}
