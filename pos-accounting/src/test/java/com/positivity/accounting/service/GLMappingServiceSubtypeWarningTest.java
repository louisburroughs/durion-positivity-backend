package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.GLMappingCreateRequest;
import com.positivity.accounting.internal.dto.GLMappingCreateResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.GLMapping;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.service.GLMappingServiceImpl;
import com.positivity.accounting.internal.service.GLMappingSubtypeValidator;
import java.time.LocalDateTime;
import java.util.List;
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
 * Unit tests for the Story H1 subtype plausibility warning in
 * GLMappingServiceImpl.createMapping (Issue #934).
 *
 * The warning is strictly non-blocking: a mapping from a cash-receipt code to
 * an implausibly-subtyped account is still created.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GLMappingService Subtype Warning Tests")
class GLMappingServiceSubtypeWarningTest {

    private static final UUID GL_ACCOUNT_ID = UUID.fromString("00000000-0000-4000-a000-000000000001");

    @Mock
    private GLMappingRepository glMappingRepository;

    @Mock
    private GLAccountRepository glAccountRepository;

    @Spy
    private GLMappingSubtypeValidator subtypeValidator = new GLMappingSubtypeValidator();

    @InjectMocks
    private GLMappingServiceImpl service;

    private GLAccount revenueAccount;

    @BeforeEach
    void setUp() {
        revenueAccount = new GLAccount();
        revenueAccount.setGlAccountId(GL_ACCOUNT_ID);
        revenueAccount.setAccountCode("4000");
        revenueAccount.setAccountName("Service Revenue");
        revenueAccount.setAccountType(AccountType.REVENUE);
        revenueAccount.setAccountSubtype(AccountSubtype.SALES);
        revenueAccount.setActivationDate(LocalDateTime.of(2025, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("Should create mapping despite implausible subtype (warning is non-blocking)")
    void shouldCreateMappingDespiteImplausibleSubtype() {
        GLMappingCreateRequest request = GLMappingCreateRequest.builder()
                .sourceSystem("POS")
                .externalCode("PAYMENT_APPLICATION")
                .glAccountId(GL_ACCOUNT_ID)
                .effectiveStartDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();

        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(revenueAccount));
        when(glMappingRepository.findOverlappingMappings(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(glMappingRepository.save(any(GLMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GLMappingCreateResponse response = service.createMapping(request);

        assertThat(response.getMapping()).isNotNull();
        assertThat(response.getMapping().getExternalCode()).isEqualTo("PAYMENT_APPLICATION");
        verify(subtypeValidator).checkCashReceiptSubtype(eq("PAYMENT_APPLICATION"), eq(revenueAccount));
        verify(glMappingRepository).save(any(GLMapping.class));
    }

    @Test
    @DisplayName("Should run plausibility check without warning for non-cash-receipt codes")
    void shouldNotWarnForUnrelatedCode() {
        GLMappingCreateRequest request = GLMappingCreateRequest.builder()
                .sourceSystem("ORDER")
                .externalCode("ORDER_COMPLETED")
                .glAccountId(GL_ACCOUNT_ID)
                .effectiveStartDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                .build();

        when(glAccountRepository.findById(GL_ACCOUNT_ID)).thenReturn(Optional.of(revenueAccount));
        when(glMappingRepository.findOverlappingMappings(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(glMappingRepository.save(any(GLMapping.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GLMappingCreateResponse response = service.createMapping(request);

        assertThat(response.getMapping()).isNotNull();
        assertThat(subtypeValidator.checkCashReceiptSubtype("ORDER_COMPLETED", revenueAccount))
                .isEmpty();
    }
}
