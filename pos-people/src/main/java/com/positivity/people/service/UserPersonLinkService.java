package com.positivity.people.service;

import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UserPersonLinkService {

    boolean linkExistsByUserId(@NonNull UUID userId);

    boolean linkExistsByUserIdAndPersonId(@NonNull UUID userId, @NonNull UUID personId);

    @NonNull
    UserPersonLinkResponse createUserLink(@NonNull UUID userId, @NonNull UUID personId);

    @NonNull
    List<UserPersonLinkResponse> getUserLinks(@NonNull UUID personId);

    @NonNull
    UserPersonLinkResponse linkUserToPerson(@NonNull LinkUserToPersonRequest request);

    void unlinkUserFromPerson(@NonNull UUID userId);

    @NonNull
    PersonResponse findPersonByUserId(@NonNull UUID userId);

    @NonNull
    List<UUID> findUserIdsByPersonId(@NonNull UUID personId);

    @NonNull
    UserPersonLinkResponse findLinkByPersonId(@NonNull UUID personId);

    /**
     * Compliance check (ADR-0015 §4): returns every ACTIVE user-person link whose
     * linked person is in an inactive status (SUSPENDED, TERMINATED, DISABLED).
     * These users should have been disabled when the person was disabled/archived.
     *
     * @return offending links; empty when none
     */
    @NonNull
    List<InactivePersonActiveUserResponse> findActiveUsersForInactivePersons();
}
