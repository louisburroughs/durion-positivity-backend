package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.service.VendorBillPostingService.Classification;
import com.positivity.accounting.internal.service.VendorBillPostingService.Leg;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entry approving a bill would post, for the work item's posting preview (CAP:550 S19, #2515): the legs {@link
 * VendorBillPostingService} builds from the bill's stored lines and the class proposed at submission, on the date an
 * approval today would post, each leg's account read through the {@code VENDOR_BILL} mapping. It writes nothing and
 * refuses nothing: a bill the posting would refuse (no proposed class, totals not reconciled, a key with no active
 * mapping) previews as no lines, never as a partial entry. The recovery of input tax and the self-assessed tax accrual
 * are decided at approval and are not part of the preview.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillApprovalPreview {

    private final VendorBillPostingService posting;
    private final VendorBillLineRepository billLines;
    private final GLMappingResolver glMappingResolver;
    private final GLAccountRepository glAccounts;

    /** The lines the approval of {@code bill} would post, debits first; empty when they cannot be known. */
    public @NonNull List<WorkItemPostingLine> preview(@NonNull VendorBill bill) {
        List<Leg> legs;
        LocalDate date;
        try {
            legs = VendorBillPostingService.legs(
                    bill,
                    billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId()),
                    new Classification(bill.getProposedDebitClass(), bill.getProposedExpenseMappingKey()),
                    VendorBillPostingService.difference(bill));
            date = posting.postingDate(bill).date();
        } catch (RuntimeException e) {
            log.debug("No posting preview for bill {}: {}", bill.getBillNumber(), e.getMessage());
            return List.of();
        }
        Map<String, UUID> accountIds = new HashMap<>();
        for (Leg leg : legs) {
            Optional<UUID> account = glMappingResolver.findGLAccount(
                    VendorBillPostingService.POSTING_CATEGORY, leg.mappingKey(), date.atStartOfDay());
            if (account.isEmpty()) {
                return List.of();
            }
            accountIds.put(leg.mappingKey(), account.get());
        }
        Map<UUID, GLAccount> accounts = new HashMap<>();
        glAccounts.findAllById(accountIds.values()).forEach(account -> accounts.put(account.getGlAccountId(), account));
        List<WorkItemPostingLine> lines = new ArrayList<>();
        for (Leg leg : legs) {
            GLAccount account = accounts.get(accountIds.get(leg.mappingKey()));
            if (account == null) {
                return List.of();
            }
            BigDecimal signed = leg.signedAmount();
            lines.add(WorkItemPostingLine.ofSigned(account.getAccountCode(), account.getAccountName(), signed));
        }
        lines.sort((a, b) -> a.side().compareTo(b.side()));
        return lines;
    }
}
