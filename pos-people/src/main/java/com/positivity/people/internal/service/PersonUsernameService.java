package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves a person's username from the {@code ext_people_contact_user_link} replica
 * (ADR-0044 §6, #875). Link facts are owned by pos-people-contact and consumed from
 * {@code people-contact.events.v1}, so resolution stays a local read.
 */
@Service
@RequiredArgsConstructor
public class PersonUsernameService {

    private final ExtUserLinkReplicaRepository linkReplicaRepository;

    /** Username for a single person, or null if unlinked. */
    public @Nullable String usernameForPerson(@NonNull UUID personId) {
        Optional<String> username = linkReplicaRepository.findByPersonIdAndStatus(personId, "ACTIVE").stream()
                .map(ExtUserLinkReplica::getUsername)
                .filter(Objects::nonNull)
                .findFirst();
        return username.orElse(null);
    }

    /** Batch personId → username for directory/list paths. */
    public @NonNull Map<UUID, String> usernamesByPersonId(@NonNull Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        for (ExtUserLinkReplica link : linkReplicaRepository.findByPersonIdIn(personIds)) {
            if (link.getUsername() != null && "ACTIVE".equals(link.getStatus())) {
                result.putIfAbsent(link.getPersonId(), link.getUsername());
            }
        }
        return result;
    }
}
