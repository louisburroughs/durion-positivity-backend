package com.positivity.peoplecontact.internal.service;

import com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1;
import com.positivity.peoplecontact.internal.dto.LinkUserToPersonRequest;
import com.positivity.peoplecontact.internal.entity.ProcessedEvent;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.peoplecontact.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies user-person-link commands from pos-security-service (amended ADR-0043, #876).
 *
 * <p>Create/remove go through {@link UserPersonLinkService}, so the confirming
 * {@code user-person-link.updated}/{@code removed} facts are emitted by the same code path the
 * REST surface uses. The {@code processed_events} idempotency row commits in the command
 * transaction, so redelivery cannot recreate a link that was removed after the original apply.
 * Permanent conflicts (username already linked to a different person, unknown person) are logged,
 * marked processed, and dropped — retrying cannot fix them.
 *
 * <p>Transaction shape (#2146): these methods are not {@code @Transactional}. The link change and
 * its processed mark commit together in one {@code REQUIRES_NEW} transaction; a permanent conflict
 * rolls that transaction back and the mark is written in a second one. {@link UserPersonLinkService}
 * is itself {@code @Transactional}, so when these methods held one transaction the conflict it
 * threw marked that transaction rollback-only, and the mark's commit failed with
 * {@code UnexpectedRollbackException} — the conflict was never recorded as processed.
 */
@Slf4j
@Service
public class LinkCommandHandler {

    private static final String OWNER = "security";

    private final Clock clock;
    private final UserPersonLinkService userPersonLinkService;
    private final ProcessedEventRepository processedEventRepository;

    /** One transaction for the link change and its mark, one for a conflict's mark. */
    private final TransactionTemplate commandTransaction;

    public LinkCommandHandler(
            Clock clock,
            UserPersonLinkService userPersonLinkService,
            ProcessedEventRepository processedEventRepository,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.userPersonLinkService = userPersonLinkService;
        this.processedEventRepository = processedEventRepository;
        this.commandTransaction = new TransactionTemplate(transactionManager);
        this.commandTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void applyCreate(@NonNull String commandEventId, @NonNull UserPersonLinkCreateRequestedV1 command) {
        try {
            LinkUserToPersonRequest request = new LinkUserToPersonRequest(command.username(), command.personId());
            if (command.linkType() != null) {
                request.setLinkType(command.linkType());
            }
            if (command.notes() != null) {
                request.setNotes(command.notes());
            }
            commandTransaction.executeWithoutResult(_ -> {
                userPersonLinkService.linkUserToPerson(request);
                markProcessed(commandEventId);
            });
            log.info(
                    "Link create command applied username={} personId={} eventId={}",
                    command.username(),
                    command.personId(),
                    commandEventId);
        } catch (com.positivity.peoplecontact.internal.exception.PersonNotFoundException e) {
            // Commands are keyed differently (person upsert by personId, link create by username),
            // so a link create can outrun its person upsert across partitions. Rethrow WITHOUT
            // marking processed: the container error handler retries with backoff and DLQs if the
            // person never materializes.
            throw e;
        } catch (UserAlreadyLinkedException e) {
            log.error(
                    "Link create command conflict (username linked elsewhere) username={} personId={} eventId={}",
                    command.username(),
                    command.personId(),
                    commandEventId,
                    e);
            commandTransaction.executeWithoutResult(_ -> markProcessed(commandEventId));
        }
    }

    public void applyRemove(@NonNull String commandEventId, @NonNull String username) {
        try {
            commandTransaction.executeWithoutResult(_ -> {
                userPersonLinkService.unlinkUserFromPerson(username);
                markProcessed(commandEventId);
            });
            log.info("Link remove command applied username={} eventId={}", username, commandEventId);
        } catch (UserPersonLinkNotFoundException e) {
            log.info("Link remove command no-op (no link) username={} eventId={}", username, commandEventId);
            commandTransaction.executeWithoutResult(_ -> markProcessed(commandEventId));
        }
    }

    private void markProcessed(String commandEventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(commandEventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }
}
