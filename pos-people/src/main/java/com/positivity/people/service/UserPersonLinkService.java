package com.positivity.people.service;

import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface UserPersonLinkService {

    @NonNull
    UserPersonLinkResponse linkUserToPerson(@NonNull LinkUserToPersonRequest request);

    void unlinkUserFromPerson(@NonNull String userId);

    @NonNull
    PersonResponse findPersonByUserId(@NonNull String userId);

    @NonNull
    List<String> findUserIdsByPersonId(@NonNull UUID personId);
}
