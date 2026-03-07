package com.positivity.people.service;

import org.jspecify.annotations.NonNull;

import java.util.Optional;
import java.util.UUID;

public interface UserPersonTranslationService {

	@NonNull UUID getPersonUuidForUser(@NonNull UUID userId);

	@NonNull Optional<UUID> getUserIdForPerson(@NonNull UUID personUuid);

	boolean isUserLinkedToPerson(@NonNull UUID userId, @NonNull UUID personUuid);

}