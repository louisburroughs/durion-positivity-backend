package com.positivity.workorder.internal.service;

import com.positivity.shared.enums.InvoiceDeliveryMethod;
import com.positivity.shared.enums.InvoiceGroupingStrategy;
import com.positivity.workorder.internal.dto.BillingRulesDTO;
import com.positivity.workorder.internal.entity.ExtBillingRulesReplica;
import com.positivity.workorder.internal.repository.ExtBillingRulesReplicaRepository;
import com.positivity.workorder.service.BillingRulesClientService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Billing-rules lookup over the event-fed {@code ext_billing_rules} replica (ADR-0044 §6, #902
 * Phase 5.6) — the replacement for the retired synchronous pos-invoice read. CAP:092 Story #98:
 * PO enforcement requires checking if a customer requires a purchase order.
 *
 * <p>Missing replica rows read as "no rules" (fail-safe: PO not required), exactly the retired
 * client's behavior on 404/timeouts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingRulesClientServiceImpl implements BillingRulesClientService {

    private final ExtBillingRulesReplicaRepository extBillingRulesReplicaRepository;

    @Override
    @NonNull
    public Optional<BillingRulesDTO> getBillingRules(@NonNull String partyId) {
        return extBillingRulesReplicaRepository.findById(partyId).map(this::toDTO);
    }

    @Override
    public boolean isPurchaseOrderRequired(@Nullable String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return false;
        }
        return getBillingRules(partyId)
                .map(BillingRulesDTO::isPurchaseOrderRequired)
                .orElse(false);
    }

    private BillingRulesDTO toDTO(@NonNull ExtBillingRulesReplica replica) {
        BillingRulesDTO dto = new BillingRulesDTO();
        dto.setPartyId(replica.getPartyId());
        dto.setPurchaseOrderRequired(replica.isPurchaseOrderRequired());
        dto.setPaymentTermsCode(replica.getPaymentTermsCode());
        dto.setInvoiceDeliveryMethod(parseEnum(InvoiceDeliveryMethod.class, replica.getInvoiceDeliveryMethod()));
        dto.setInvoiceGroupingStrategy(parseEnum(InvoiceGroupingStrategy.class, replica.getInvoiceGroupingStrategy()));
        dto.setUpdatedAt(replica.getUpdatedAt());
        return dto;
    }

    private static <E extends Enum<E>> @Nullable E parseEnum(@NonNull Class<E> type, @Nullable String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
