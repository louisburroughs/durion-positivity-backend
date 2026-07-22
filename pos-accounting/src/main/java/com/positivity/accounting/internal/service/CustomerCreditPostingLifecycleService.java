package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write-back of the GL posting lifecycle onto a customer-credit draw-down (issue #992).
 *
 * <p>Exists so the posting handler never touches a repository directly — repositories are
 * service-layer-only by the module's ArchUnit rule. Runs with MANDATORY propagation so the
 * link is written in the handler's own transaction, alongside the journal entry and the
 * idempotency key: all three land together or none do.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerCreditPostingLifecycleService {

    private final Clock clock;
    private final CustomerCreditTransactionRepository creditTransactionRepository;

    /**
     * Record the relieving journal entry on its draw-down row, so the subledger points at the
     * entry that discharged it.
     *
     * @param creditTransactionId the draw-down that was posted
     * @param journalEntryId      the journal entry that relieved the liability
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReliefPosting(@NonNull UUID creditTransactionId, @NonNull UUID journalEntryId) {
        creditTransactionRepository
                .findById(creditTransactionId)
                .ifPresentOrElse(
                        transaction -> {
                            transaction.setGlJournalEntryId(journalEntryId);
                            transaction.setGlPostedAt(Instant.now(clock));
                            creditTransactionRepository.save(transaction);
                        },
                        () -> log.warn(
                                "Posted customer credit relief {} but its draw-down row {} is gone; "
                                        + "posting lifecycle not linked",
                                journalEntryId,
                                creditTransactionId));
    }
}
