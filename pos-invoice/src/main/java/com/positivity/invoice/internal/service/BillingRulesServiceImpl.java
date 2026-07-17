package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.config.InvoiceEventPublisher;
import com.positivity.invoice.internal.dto.BillingRulesDTO;
import com.positivity.invoice.internal.entity.BillingRules;
import com.positivity.invoice.internal.enums.InvoiceDeliveryMethod;
import com.positivity.invoice.internal.enums.InvoiceGroupingStrategy;
import com.positivity.invoice.internal.repository.BillingRulesRepository;
import com.positivity.invoice.service.BillingRulesService;
import com.positivity.security.common.SecurityContextHelper;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final InvoiceEventPublisher invoiceEventPublisher;

    // Default values from configuration
    @Value("${billing.defaults.purchase-order-required:false}")
    private boolean defaultPurchaseOrderRequired;

    @Value("${billing.defaults.payment-terms-code:NET_30}")
    private String defaultPaymentTermsCode;

    @Value("${billing.defaults.invoice-delivery-method:EMAIL}")
    private String defaultInvoiceDeliveryMethod;

    @Value("${billing.defaults.invoice-grouping-strategy:PER_WORKORDER}")
    private String defaultInvoiceGroupingStrategy;

    public BillingRulesServiceImpl(
            @NonNull BillingRulesRepository repository, @NonNull InvoiceEventPublisher invoiceEventPublisher) {
        this.repository = repository;
        this.invoiceEventPublisher = invoiceEventPublisher;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public Optional<BillingRulesDTO> getBillingRules(@NonNull String partyId) {
        return repository.findByPartyId(partyId).map(this::toDTO);
    }

    @Override
    @NonNull
    public BillingRulesDTO saveBillingRules(@NonNull BillingRulesDTO billingRulesDTO, @NonNull String updatedBy) {
        Optional<BillingRules> existing = repository.findByPartyId(billingRulesDTO.getPartyId());

        BillingRules billingRules;
        if (existing.isPresent()) {
            billingRules = existing.get();
            if (log.isInfoEnabled()) {
                log.info("Updating billing rules for partyId(mask)={}", maskForLog(billingRulesDTO.getPartyId()));
            }
        } else {
            billingRules = new BillingRules();
            billingRules.setPartyId(billingRulesDTO.getPartyId());
            if (log.isInfoEnabled()) {
                log.info("Creating billing rules for partyId(mask)={}", maskForLog(billingRulesDTO.getPartyId()));
            }
        }

        billingRules.setPurchaseOrderRequired(billingRulesDTO.isPurchaseOrderRequired());
        billingRules.setPaymentTermsCode(billingRulesDTO.getPaymentTermsCode());
        billingRules.setInvoiceDeliveryMethod(billingRulesDTO.getInvoiceDeliveryMethod());
        billingRules.setInvoiceGroupingStrategy(billingRulesDTO.getInvoiceGroupingStrategy());
        billingRules.setUpdatedBy(updatedBy);

        BillingRules saved = repository.save(billingRules);
        invoiceEventPublisher.publishBillingRulesUpdated(saved);
        return toDTO(saved);
    }

    @Override
    @NonNull
    public BillingRulesDTO createDefaultBillingRules(@NonNull String partyId) {
        // Idempotent - check if exists first
        Optional<BillingRules> existing = repository.findByPartyId(partyId);
        if (existing.isPresent()) {
            if (log.isDebugEnabled()) {
                log.debug("Billing rules already exist for partyId(mask)={}, returning existing", maskForLog(partyId));
            }
            return toDTO(existing.get());
        }
        if (log.isInfoEnabled()) {
            log.info("Creating default billing rules for new commercial account partyId(mask)={}", maskForLog(partyId));
        }
        BillingRules billingRules = new BillingRules();
        billingRules.setPartyId(partyId);
        billingRules.setPurchaseOrderRequired(defaultPurchaseOrderRequired);
        billingRules.setPaymentTermsCode(defaultPaymentTermsCode);
        billingRules.setInvoiceDeliveryMethod(InvoiceDeliveryMethod.valueOf(defaultInvoiceDeliveryMethod));
        billingRules.setInvoiceGroupingStrategy(InvoiceGroupingStrategy.valueOf(defaultInvoiceGroupingStrategy));
        billingRules.setUpdatedBy(SYSTEM_USER);

        BillingRules saved = repository.save(billingRules);
        invoiceEventPublisher.publishBillingRulesUpdated(saved);
        return toDTO(saved);
    }

    @Override
    @NonNull
    public String getCurrentUsername() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER);
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
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
