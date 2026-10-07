package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.RegisterFloatChangeRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A float entry reversed through {@code POST /v1/accounting/journal-entries/{id}/reverse} (#2511 PROPOSED 7;
 * ADR-0047 corrections by reversal): the register's amount is re-derived from its float entries still
 * standing, a {@code REVERSAL} history row is written and {@code accounting.float.changed} is queued. A
 * reversed go-live no longer counts, so a new go-live is accepted ("correction = reverse and re-run").
 *
 * <p>It hears the in-process event {@code JournalEntryServiceImpl} publishes after a reversal and runs in
 * that transaction, as {@code BankReconciliationLedgerChangeService} does; it never refuses the reversal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterFloatReversalReaction {

    static final String AUDIT_REVERSAL = "REGISTER_FLOAT_REVERSAL";

    private static final List<RegisterFloatChangeKind> POSTING_KINDS =
            List.of(RegisterFloatChangeKind.GO_LIVE, RegisterFloatChangeKind.CHANGE);

    private final RegisterFloatRepository floats;
    private final RegisterFloatChangeRepository changes;
    private final AccountingAuditLogRepository auditLogs;
    private final RegisterFloatFacts facts;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReversed(@NonNull LedgerReversalApplied reversed) {
        changes.findByJournalEntryIdAndKindIn(reversed.originalJournalEntryId(), POSTING_KINDS)
                .filter(change -> change.getReversalJournalEntryId() == null)
                .ifPresent(change -> rederive(change, reversed));
    }

    private void rederive(RegisterFloatChange reversedChange, LedgerReversalApplied reversed) {
        RegisterFloat registerFloat =
                floats.lockById(reversedChange.getRegisterFloatId()).orElseThrow();
        reversedChange.setReversalJournalEntryId(reversed.reversalJournalEntryId());
        changes.saveAndFlush(reversedChange);
        if (reversedChange.getKind() == RegisterFloatChangeKind.GO_LIVE) {
            registerFloat.setGoLiveJournalEntryId(null);
        }

        BigDecimal previous = registerFloat.getAmount();
        BigDecimal standing = changes
                .findByRegisterFloatIdAndKindInAndReversalJournalEntryIdIsNull(
                        registerFloat.getRegisterFloatId(), POSTING_KINDS)
                .stream()
                .map(change -> change.getNewAmount().subtract(change.getPreviousAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        registerFloat.setAmount(standing);
        if (standing.signum() < 0) {
            log.warn(
                    "Register {} float re-derived to {} after reversal {}: the reversed entry was followed by"
                            + " changes that relied on it",
                    registerFloat.getRegisterId(),
                    standing,
                    reversed.reversalJournalEntryId());
        }
        RegisterFloat saved = floats.saveAndFlush(registerFloat);

        RegisterFloatChange row = new RegisterFloatChange();
        row.setRegisterFloatId(saved.getRegisterFloatId());
        row.setRegisterId(saved.getRegisterId());
        row.setLocationId(saved.getLocationId());
        row.setKind(RegisterFloatChangeKind.REVERSAL);
        row.setPreviousAmount(previous);
        row.setNewAmount(saved.getAmount());
        row.setJournalEntryId(reversed.reversalJournalEntryId());
        row.setReversedChangeId(reversedChange.getChangeId());
        row.setEffectiveDate(reversed.reversalDate());
        row.setJustification(
                "Reversal of the " + reversedChange.getKind().name().toLowerCase(java.util.Locale.ROOT)
                        + " float entry " + reversed.originalJournalEntryId());
        row.setActor(reversed.actor());
        changes.saveAndFlush(row);

        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(RegisterFloatServiceImpl.AUDIT_ENTITY_TYPE);
        audit.setEntityId(saved.getRegisterFloatId());
        audit.setOperation(AUDIT_REVERSAL);
        audit.setUserId(reversed.actor());
        audit.setJustification(row.getJustification());
        audit.setOldValue("amount=" + previous.toPlainString());
        audit.setNewValue("registerId=" + saved.getRegisterId() + ";amount="
                + saved.getAmount().toPlainString()
                + ";reversedJournalEntryId=" + reversed.originalJournalEntryId() + ";reversalJournalEntryId="
                + reversed.reversalJournalEntryId());
        auditLogs.save(audit);
        facts.changed(saved, RegisterFloatFacts.factOf(saved, row, previous), reversed.actor());
    }
}
