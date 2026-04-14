package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import com.positivity.people.service.UserPersonTranslationService;
import jakarta.persistence.EntityNotFoundException;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserPersonTranslationServiceImpl implements UserPersonTranslationService {

    private final UserPersonLinkRepository userPersonLinkRepository;

    public UserPersonTranslationServiceImpl(@NonNull UserPersonLinkRepository userPersonLinkRepository) {
        this.userPersonLinkRepository = userPersonLinkRepository;
    }

    @Override
    @NonNull public UUID getPersonUuidForUser(@NonNull UUID userId) {
        return userPersonLinkRepository.findByUserId(userId)
            .map(link -> link.getPersonId())
            .orElseThrow(() -> new EntityNotFoundException("No person link found for userId: " + userId));
    }

    @Override
    @NonNull public Optional<UUID> getUserIdForPerson(@NonNull UUID personUuid) {
        return userPersonLinkRepository.findByPerson_IdAndStatus(personUuid, UserLinkStatus.ACTIVE)
            .map(UserPersonLink::getUserId);
    }

    @Override
    public boolean isUserLinkedToPerson(@NonNull UUID userId, @NonNull UUID personUuid) {
        return userPersonLinkRepository.existsByUserIdAndPerson_Id(userId, personUuid);
    }

}
