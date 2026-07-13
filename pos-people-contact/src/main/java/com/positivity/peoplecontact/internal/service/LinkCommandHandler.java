package com.positivity.peoplecontact.internal.service;

import com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1;
import com.positivity.peoplecontact.internal.dto.LinkUserToPersonRequest;
import com.positivity.peoplecontact.internal.entity.ProcessedEvent;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.peoplecontact.internal.repository.ProcessedEventRepository;
import com.positivity.peoplecontact.service.UserPersonLinkService;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies user-person-link commands from pos-security-service (amended ADR-0043, #876).
 *
 * <p>Create/remove go through {@link UserPersonLinkService}, so the confirming
 * {@code user-person-link.updated}/{@code removed} facts are emitted by the same code path the
 * REST surface uses. The {@code processed_events} idempotency row commits in the command
 * transaction, so redelivery cannot recreate a link that was removed after the original apply.
 * Permanent conflicts (username already linked to a different person, unknown person) are logged,
 * marked processed, and dropped — retrying cannot fix them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkCommandHandler {

    private static final String OWNER = "security";

    private final Clock clock;
    private final UserPersonLinkService userPersonLinkService;
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void applyCreate(@NonNull String commandEventId, @NonNull UserPersonLinkCreateRequestedV1 command) {
        try {
            LinkUserToPersonRequest request = new LinkUserToPersonRequest(command.username(), command.personId());
            if (command.linkType() != null) {
                request.setLinkType(command.linkType());
            }
            if (command.notes() != null) {
                request.setNotes(command.notes());
            }
            userPersonLinkService.linkUserToPerson(request);
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
        }
        markProcessed(commandEventId);
    }

    @Transactional
    public void applyRemove(@NonNull String commandEventId, @NonNull String username) {
        try {
            userPersonLinkService.unlinkUserFromPerson(username);
            log.info("Link remove command applied username={} eventId={}", username, commandEventId);
        } catch (UserPersonLinkNotFoundException e) {
            log.info("Link remove command no-op (no link) username={} eventId={}", username, commandEventId);
        }
        markProcessed(commandEventId);
    }

    private void markProcessed(String commandEventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(commandEventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }
}
