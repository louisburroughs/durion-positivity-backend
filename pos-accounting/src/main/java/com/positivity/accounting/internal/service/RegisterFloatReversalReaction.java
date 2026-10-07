package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.enums.RegisterFloatRelocationReason;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.RegisterFloatChangeRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * <p>A register that has moved (#2571, AW32) adds three rules. A relocation entry is never reversed: 409
 * {@code FLOAT_RELOCATION_NOT_REVERSIBLE}, a wrong move is corrected by moving again. A go-live or change
 * reversal may not predate the register's latest move: 422 {@code FLOAT_REVERSAL_BEFORE_RELOCATION}. And the
 * reversal of an entry whose 1080 line sits at a location the register has left also posts, dated the reversal
 * date, the reclass that moves the reversed amount from that location to the current one, recorded as a {@code
 * RELOCATION} row with reason {@code REVERSAL_FOLLOW_UP}; so 1080 still sums, per location, to the float where
 * the register is held and to zero elsewhere.
 *
 * <p>Both rows it writes, and the fact, state the register float's currency (#2577; ADR-0067 R-1).
 *
 * <p>It hears the in-process event {@code JournalEntryServiceImpl} publishes after a reversal and runs in
 * that transaction, as {@code BankReconciliationLedgerChangeService} does; a refusal rolls the reversal back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterFloatReversalReaction {

    static final String AUDIT_REVERSAL = "REGISTER_FLOAT_REVERSAL";

    private static final List<RegisterFloatChangeKind> POSTING_KINDS =
            List.of(RegisterFloatChangeKind.GO_LIVE, RegisterFloatChangeKind.CHANGE);
    private static final List<RegisterFloatChangeKind> ENTRY_KINDS = List.of(
            RegisterFloatChangeKind.GO_LIVE, RegisterFloatChangeKind.CHANGE, RegisterFloatChangeKind.RELOCATION);

    private final RegisterFloatRepository floats;
    private final RegisterFloatChangeRepository changes;
    private final AccountingAuditLogRepository auditLogs;
    private final RegisterFloatFacts facts;
    private final JournalEntryService journalEntryService;
    private final GLMappingResolver glMappingResolver;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReversed(@NonNull LedgerReversalApplied reversed) {
        changes.findByJournalEntryIdAndKindIn(reversed.originalJournalEntryId(), ENTRY_KINDS)
                .ifPresent(change -> {
                    if (change.getKind() == RegisterFloatChangeKind.RELOCATION) {
                        log.warn(
                                "Refused the reversal of register {} relocation entry {}",
                                change.getRegisterId(),
                                reversed.originalJournalEntryId());
                        throw new CashSetupException(
                                CashSetupException.Code.FLOAT_RELOCATION_NOT_REVERSIBLE,
                                "A register float relocation entry is never reversed; correct a wrong move by moving"
                                        + " the register again");
                    }
                    if (change.getReversalJournalEntryId() == null) {
                        rederive(change, reversed);
                    }
                });
    }

    private void rederive(RegisterFloatChange reversedChange, LedgerReversalApplied reversed) {
        RegisterFloat registerFloat =
                floats.lockById(reversedChange.getRegisterFloatId()).orElseThrow();
        // After a move, a reversal dated earlier would take the amount off a location the register had left.
        Optional<LocalDate> relocated = RegisterFloatServiceImpl.latestEffectiveDate(
                changes, registerFloat.getRegisterFloatId(), RegisterFloatServiceImpl.RELOCATIONS);
        if (relocated.isPresent() && reversed.reversalDate().isBefore(relocated.get())) {
            throw new CashSetupException(
                    CashSetupException.Code.FLOAT_REVERSAL_BEFORE_RELOCATION,
                    RegisterFloatServiceImpl.relocationMessage(
                            registerFloat.getRegisterId(), relocated.get(), "reversal"));
        }
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

        if (!reversedChange.getLocationId().equals(saved.getLocationId())) {
            followUp(saved, reversedChange, reversed);
        }

        RegisterFloatChange row = new RegisterFloatChange();
        row.setRegisterFloatId(saved.getRegisterFloatId());
        row.setRegisterId(saved.getRegisterId());
        row.setLocationId(saved.getLocationId());
        row.setKind(RegisterFloatChangeKind.REVERSAL);
        row.setPreviousAmount(previous);
        row.setNewAmount(saved.getAmount());
        row.setCurrencyCode(saved.getCurrencyCode());
        row.setJournalEntryId(reversed.reversalJournalEntryId());
        row.setReversedChangeId(reversedChange.getChangeId());
        row.setEffectiveDate(reversed.reversalDate());
        row.setJustification(
                "Reversal of the " + kindName(reversedChange) + " float entry " + reversed.originalJournalEntryId());
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
                + saved.getAmount().toPlainString() + ";currencyCode=" + saved.getCurrencyCode()
                + ";reversedJournalEntryId=" + reversed.originalJournalEntryId() + ";reversalJournalEntryId="
                + reversed.reversalJournalEntryId());
        auditLogs.save(audit);
        facts.changed(saved, RegisterFloatFacts.factOf(saved, row, previous, null), reversed.actor());
    }

    /**
     * The reversal took the entry's amount off 1080 at the entry's location, which the register has left: move that
     * amount from there to the register's current location, dated the reversal date, under the reversal's own
     * period override (AW32). The register does not move, so no fact is queued for it.
     */
    private void followUp(
            RegisterFloat registerFloat, RegisterFloatChange reversedChange, LedgerReversalApplied reversed) {
        BigDecimal reversedAmount = reversedChange.getPreviousAmount().subtract(reversedChange.getNewAmount());
        JournalEntryResponse reclass = RegisterFloatServiceImpl.reclass(
                journalEntryService,
                glMappingResolver,
                registerFloat.getRegisterId(),
                reversedChange.getLocationId(),
                registerFloat.getLocationId(),
                reversedAmount,
                reversed.reversalDate(),
                "Relocate reversed float of register " + registerFloat.getRegisterId(),
                reversed.overrideJustification());
        log.info(
                "Register {} reversal {} of an entry made at location {}: reclass {} moves {} to location {}",
                registerFloat.getRegisterId(),
                reversed.reversalJournalEntryId(),
                reversedChange.getLocationId(),
                reclass.getJournalEntryId(),
                reversedAmount,
                registerFloat.getLocationId());

        RegisterFloatChange row = new RegisterFloatChange();
        row.setRegisterFloatId(registerFloat.getRegisterFloatId());
        row.setRegisterId(registerFloat.getRegisterId());
        row.setLocationId(registerFloat.getLocationId());
        row.setPreviousLocationId(reversedChange.getLocationId());
        row.setKind(RegisterFloatChangeKind.RELOCATION);
        row.setReason(RegisterFloatRelocationReason.REVERSAL_FOLLOW_UP);
        row.setPreviousAmount(registerFloat.getAmount());
        row.setNewAmount(registerFloat.getAmount());
        row.setCurrencyCode(registerFloat.getCurrencyCode());
        row.setJournalEntryId(reclass.getJournalEntryId());
        row.setEffectiveDate(reversed.reversalDate());
        row.setJustification("Follow-up of the reversal of the " + kindName(reversedChange) + " float entry "
                + reversed.originalJournalEntryId() + ": the reversed amount moves to the register's location");
        row.setOverrideJustification(reversed.overrideJustification());
        row.setActor(reversed.actor());
        changes.saveAndFlush(row);

        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(RegisterFloatServiceImpl.AUDIT_ENTITY_TYPE);
        audit.setEntityId(registerFloat.getRegisterFloatId());
        audit.setOperation(RegisterFloatServiceImpl.AUDIT_RELOCATION);
        audit.setUserId(reversed.actor());
        audit.setJustification(row.getJustification());
        audit.setOldValue("locationId=" + row.getPreviousLocationId() + ";amount=" + reversedAmount.toPlainString());
        audit.setNewValue(
                RegisterFloatServiceImpl.relocationAuditValue(row) + ";movedAmount=" + reversedAmount.toPlainString());
        auditLogs.save(audit);
    }

    private static String kindName(RegisterFloatChange change) {
        return change.getKind().name().toLowerCase(Locale.ROOT);
    }
}
