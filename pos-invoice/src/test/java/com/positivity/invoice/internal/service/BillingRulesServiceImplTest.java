package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.BillingRulesDTO;
import com.positivity.invoice.internal.entity.BillingRules;
import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import com.positivity.invoice.internal.repository.BillingRulesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingRulesServiceImplTest {

    @Mock
    private BillingRulesRepository billingRulesRepository;

    @InjectMocks
    private BillingRulesServiceImpl billingRulesService;

    private BillingRules existingRules;
    private final String partyId = "party-001";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(billingRulesService, "defaultPurchaseOrderRequired", false);
        ReflectionTestUtils.setField(billingRulesService, "defaultPaymentTermsCode", "NET_30");
        ReflectionTestUtils.setField(billingRulesService, "defaultInvoiceDeliveryMethod", "EMAIL");
        ReflectionTestUtils.setField(billingRulesService, "defaultInvoiceGroupingStrategy", "PER_WORKORDER");

        existingRules = new BillingRules();
        existingRules.setId(UUID.randomUUID());
        existingRules.setPartyId(partyId);
        existingRules.setPurchaseOrderRequired(false);
        existingRules.setPaymentTermsCode("NET_30");
        existingRules.setInvoiceDeliveryMethod(InvoiceDeliveryMethod.EMAIL);
        existingRules.setInvoiceGroupingStrategy(InvoiceGroupingStrategy.PER_WORKORDER);
        existingRules.setUpdatedBy("system");
    }

    // ---- getBillingRules ----

    @Test
    void getBillingRules_shouldReturnDTO_whenExistingRulesFound() {
        when(billingRulesRepository.findByPartyId(partyId)).thenReturn(Optional.of(existingRules));

        Optional<BillingRulesDTO> result = billingRulesService.getBillingRules(partyId);

        assertThat(result).isPresent();
        assertThat(result.get().getPartyId()).isEqualTo(partyId);
    }

    @Test
    void getBillingRules_shouldReturnEmpty_whenNoRulesForParty() {
        when(billingRulesRepository.findByPartyId(partyId)).thenReturn(Optional.empty());

        Optional<BillingRulesDTO> result = billingRulesService.getBillingRules(partyId);

        assertThat(result).isEmpty();
    }

    // ---- saveBillingRules ----

    @Test
    void saveBillingRules_shouldUpdateExisting_whenRulesAlreadyExist() {
        BillingRulesDTO dto = new BillingRulesDTO();
        dto.setPartyId(partyId);
        dto.setPurchaseOrderRequired(true);
        dto.setPaymentTermsCode("NET_60");
        dto.setInvoiceDeliveryMethod(InvoiceDeliveryMethod.MAIL);
        dto.setInvoiceGroupingStrategy(InvoiceGroupingStrategy.PER_WORKORDER);

        when(billingRulesRepository.findByPartyId(partyId)).thenReturn(Optional.of(existingRules));
        when(billingRulesRepository.save(any())).thenReturn(existingRules);

        BillingRulesDTO result = billingRulesService.saveBillingRules(dto, "user1");

        assertThat(result).isNotNull();
        verify(billingRulesRepository).save(existingRules);
    }

    @Test
    void saveBillingRules_shouldCreateNew_whenNoExistingRules() {
        BillingRulesDTO dto = new BillingRulesDTO();
        dto.setPartyId(partyId);
        dto.setPurchaseOrderRequired(false);
        dto.setPaymentTermsCode("NET_30");
        dto.setInvoiceDeliveryMethod(InvoiceDeliveryMethod.EMAIL);
        dto.setInvoiceGroupingStrategy(InvoiceGroupingStrategy.PER_WORKORDER);

        when(billingRulesRepository.findByPartyId(partyId)).thenReturn(Optional.empty());
        when(billingRulesRepository.save(any())).thenReturn(existingRules);

        BillingRulesDTO result = billingRulesService.saveBillingRules(dto, "user1");

        assertThat(result).isNotNull();
    }

    // ---- createDefaultBillingRules ----

    @Test
    void createDefaultBillingRules_shouldCreateNew_whenNoneExist() {
        when(billingRulesRepository.findByPartyId(partyId)).thenReturn(Optional.empty());
        when(billingRulesRepository.save(any())).thenReturn(existingRules);

        BillingRulesDTO result = billingRulesService.createDefaultBillingRules(partyId);

        assertThat(result).isNotNull();
        verify(billingRulesRepository).save(any());
    }

    @Test
    void createDefaultBillingRules_shouldReturnExisting_whenRulesAlreadyExist() {
        when(billingRulesRepository.findByPartyId(partyId)).thenReturn(Optional.of(existingRules));

        BillingRulesDTO result = billingRulesService.createDefaultBillingRules(partyId);

        assertThat(result).isNotNull();
        verify(billingRulesRepository).findByPartyId(partyId);
    }
}
