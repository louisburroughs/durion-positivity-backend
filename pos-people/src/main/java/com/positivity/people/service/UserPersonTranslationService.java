package com.positivity.people.service;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UserPersonTranslationService {

    @NonNull
    UUID getPersonUuidForUser(@NonNull UUID userId);

    @NonNull
    Optional<UUID> getUserIdForPerson(@NonNull UUID personUuid);

    boolean isUserLinkedToPerson(@NonNull UUID userId, @NonNull UUID personUuid);
}
