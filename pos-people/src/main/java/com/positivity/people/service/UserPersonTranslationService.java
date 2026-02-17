package com.positivity.people.service;

import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface UserPersonTranslationService {

    @NonNull
    UUID getPersonUuidForUser(@NonNull String userId);

    @NonNull
    Optional<String> getUserIdForPerson(@NonNull UUID personUuid);

    boolean isUserLinkedToPerson(@NonNull String userId, @NonNull UUID personUuid);
}