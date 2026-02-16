package com.positivity.people.service;

import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserPersonTranslationService {

    private final UserPersonLinkRepository userPersonLinkRepository;

    public UserPersonTranslationService(@NonNull UserPersonLinkRepository userPersonLinkRepository) {
        this.userPersonLinkRepository = userPersonLinkRepository;
    }

    @NonNull
    public UUID getUserIdByPersonId(@NonNull UUID personId) {
        return userPersonLinkRepository.findByPersonId(personId)
                .stream()
                .findFirst()
                .map(link -> parseUserId(link.getUserId(), personId))
                .orElseThrow(() -> new NotFoundException("No user link found for personId: " + personId));
    }

    @NonNull
    public UUID getPersonIdByUserId(@NonNull UUID userId) {
        return userPersonLinkRepository.findByUserId(userId.toString())
                .map(link -> link.getPersonId())
                .orElseThrow(() -> new NotFoundException("No person link found for userId: " + userId));
    }

    @NonNull
    private UUID parseUserId(@NonNull String rawUserId, @NonNull UUID personId) {
        try {
            return UUID.fromString(rawUserId);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("Linked userId is not a valid UUID for personId: " + personId);
        }
    }
}