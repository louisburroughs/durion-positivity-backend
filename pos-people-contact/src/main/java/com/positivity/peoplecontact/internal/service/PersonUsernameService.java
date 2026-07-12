package com.positivity.peoplecontact.internal.service;

import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import com.positivity.peoplecontact.internal.repository.UserPersonLinkRepository;
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
 * Resolves a person's username from the user_person_link mapping. The username (the credential
 * identifier owned by pos-security) is stored directly on the link, so resolution is a local
 * read — no pos-security round-trip. Username is not a Person attribute.
 */
@Service
@RequiredArgsConstructor
public class PersonUsernameService {

    private final UserPersonLinkRepository linkRepository;

    /** Username for a single person, or null if unlinked. */
    public @Nullable String usernameForPerson(@NonNull UUID personId) {
        Optional<String> username = linkRepository.findByPerson_Id(personId).stream()
                .map(UserPersonLink::getUsername)
                .filter(Objects::nonNull)
                .findFirst();
        return username.isPresent() ? username.get() : null;
    }

    /** Batch personId → username for directory/list paths. */
    public @NonNull Map<UUID, String> usernamesByPersonId(@NonNull Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        for (UserPersonLink link : linkRepository.findByPerson_IdIn(personIds)) {
            if (link.getPerson() != null && link.getUsername() != null) {
                result.putIfAbsent(link.getPerson().getId(), link.getUsername());
            }
        }
        return result;
    }
}
