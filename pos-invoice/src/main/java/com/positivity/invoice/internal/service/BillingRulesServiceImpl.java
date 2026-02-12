package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.BillingRulesDTO;
import com.positivity.invoice.internal.entity.BillingRules;
import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import com.positivity.invoice.internal.repository.BillingRulesRepository;
import com.positivity.invoice.service.BillingRulesService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.positivity.security.common.SecurityContextHelper;

import java.util.Optional;

/**
 * Implementation of BillingRulesService.
 * CAP:092 - Preferences & Billing Rules
 */
@Service
@Transactional
public class BillingRulesServiceImpl implements BillingRulesService {

    private static final Logger log = LoggerFactory.getLogger(BillingRulesServiceImpl.class);
    private static final String SYSTEM_USER = "system";

    private final BillingRulesRepository repository;

    // Default values from configuration
    @Value("${billing.defaults.purchase-order-required:false}")
    private boolean defaultPurchaseOrderRequired;

    @Value("${billing.defaults.payment-terms-code:NET_30}")
    private String defaultPaymentTermsCode;

    @Value("${billing.defaults.invoice-delivery-method:EMAIL}")
    private String defaultInvoiceDeliveryMethod;

    @Value("${billing.defaults.invoice-grouping-strategy:PER_WORKORDER}")
    private String defaultInvoiceGroupingStrategy;

    public BillingRulesServiceImpl(@NonNull BillingRulesRepository repository) {
        this.repository = repository;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Optional<BillingRulesDTO> getBillingRules(@NonNull String partyId) {
        return repository.findByPartyId(partyId)
                .map(this::toDTO);
    }

    @Override
    @NonNull
    public BillingRulesDTO saveBillingRules(@NonNull BillingRulesDTO billingRulesDTO, @NonNull String updatedBy) {
        Optional<BillingRules> existing = repository.findByPartyId(billingRulesDTO.getPartyId());

        BillingRules billingRules;
        if (existing.isPresent()) {
            billingRules = existing.get();
            log.info("Updating billing rules for partyId={}", billingRulesDTO.getPartyId());
        } else {
            billingRules = new BillingRules();
            billingRules.setPartyId(billingRulesDTO.getPartyId());
            log.info("Creating billing rules for partyId={}", billingRulesDTO.getPartyId());
        }

        billingRules.setPurchaseOrderRequired(billingRulesDTO.isPurchaseOrderRequired());
        billingRules.setPaymentTermsCode(billingRulesDTO.getPaymentTermsCode());
        billingRules.setInvoiceDeliveryMethod(billingRulesDTO.getInvoiceDeliveryMethod());
        billingRules.setInvoiceGroupingStrategy(billingRulesDTO.getInvoiceGroupingStrategy());
        billingRules.setUpdatedBy(updatedBy);

        BillingRules saved = repository.save(billingRules);
        return toDTO(saved);
    }

    @Override
    @NonNull
    public BillingRulesDTO createDefaultBillingRules(@NonNull String partyId) {
        // Idempotent - check if exists first
        Optional<BillingRules> existing = repository.findByPartyId(partyId);
        if (existing.isPresent()) {
            log.debug("Billing rules already exist for partyId={}, returning existing", partyId);
            return toDTO(existing.get());
        }

        log.info("Creating default billing rules for new commercial account partyId={}", partyId);

        BillingRules billingRules = new BillingRules();
        billingRules.setPartyId(partyId);
        billingRules.setPurchaseOrderRequired(defaultPurchaseOrderRequired);
        billingRules.setPaymentTermsCode(defaultPaymentTermsCode);
        billingRules.setInvoiceDeliveryMethod(InvoiceDeliveryMethod.valueOf(defaultInvoiceDeliveryMethod));
        billingRules.setInvoiceGroupingStrategy(InvoiceGroupingStrategy.valueOf(defaultInvoiceGroupingStrategy));
        billingRules.setUpdatedBy(SYSTEM_USER);

        BillingRules saved = repository.save(billingRules);
        return toDTO(saved);
    }

    @Override
    @NonNull
    public String getCurrentUserId() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER);
    }

    private BillingRulesDTO toDTO(@NonNull BillingRules entity) {
        BillingRulesDTO dto = new BillingRulesDTO();
        dto.setId(entity.getId());
        dto.setPartyId(entity.getPartyId());
        dto.setPurchaseOrderRequired(entity.isPurchaseOrderRequired());
        dto.setPaymentTermsCode(entity.getPaymentTermsCode());
        dto.setInvoiceDeliveryMethod(entity.getInvoiceDeliveryMethod());
        dto.setInvoiceGroupingStrategy(entity.getInvoiceGroupingStrategy());
        dto.setVersion(entity.getVersion());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setUpdatedBy(entity.getUpdatedBy());
        return dto;
    }
}
