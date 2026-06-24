package com.positivity.people.internal.service;

import com.positivity.people.internal.client.SecurityServiceClient;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves a person's username from pos-security (the system of record for credentials)
 * via the user_person_link mapping (personId → userId → security user). Username is no
 * longer stored on Person.
 */
@Service
@RequiredArgsConstructor
public class PersonUsernameService {

    private final UserPersonLinkRepository linkRepository;
    private final SecurityServiceClient securityServiceClient;

    /** Username for a single person, or null if unlinked / not resolvable. */
    public @Nullable String usernameForPerson(@NonNull UUID personId) {
        return linkRepository.findByPerson_Id(personId).stream()
                .map(UserPersonLink::getUserId)
                .filter(Objects::nonNull)
                .findFirst()
                .flatMap(securityServiceClient::getUserById)
                .map(com.positivity.people.internal.client.dto.User::getUsername)
                .orElse(null);
    }

    /** Batch personId → username (one security list call), for directory/list paths. */
    public @NonNull Map<UUID, String> usernamesByPersonId(@NonNull Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> userIdByPerson = linkRepository.findByPerson_IdIn(personIds).stream()
                .filter(link -> link.getUserId() != null && link.getPerson() != null)
                .collect(Collectors.toMap(
                        link -> link.getPerson().getId(), UserPersonLink::getUserId, (existing, ignored) -> existing));
        Map<UUID, String> usernameByUserId = securityServiceClient.getUsernamesByIds(userIdByPerson.values());
        Map<UUID, String> result = new HashMap<>();
        userIdByPerson.forEach((personId, userId) -> {
            String username = usernameByUserId.get(userId);
            if (username != null) {
                result.put(personId, username);
            }
        });
        return result;
    }
}
