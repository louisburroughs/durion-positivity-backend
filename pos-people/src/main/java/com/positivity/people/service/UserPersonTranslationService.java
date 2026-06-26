package com.positivity.people.service;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UserPersonTranslationService {

    @NonNull
    UUID getPersonUuidForUser(@NonNull String username);

    @NonNull
    Optional<String> getUsernameForPerson(@NonNull UUID personUuid);

    boolean isUserLinkedToPerson(@NonNull String username, @NonNull UUID personUuid);
}
