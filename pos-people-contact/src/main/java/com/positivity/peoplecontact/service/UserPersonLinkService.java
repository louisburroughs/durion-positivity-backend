package com.positivity.peoplecontact.service;

import com.positivity.peoplecontact.internal.dto.LinkUserToPersonRequest;
import com.positivity.peoplecontact.internal.dto.PersonResponse;
import com.positivity.peoplecontact.internal.dto.UserPersonLinkResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface UserPersonLinkService {

    boolean linkExistsByUsername(@NonNull String username);

    boolean linkExistsByUsernameAndPersonId(@NonNull String username, @NonNull UUID personId);

    @NonNull
    UserPersonLinkResponse createUserLink(@NonNull String username, @NonNull UUID personId);

    @NonNull
    List<UserPersonLinkResponse> getUserLinks(@NonNull UUID personId);

    @NonNull
    UserPersonLinkResponse linkUserToPerson(@NonNull LinkUserToPersonRequest request);

    void unlinkUserFromPerson(@NonNull String username);

    @NonNull
    PersonResponse findPersonByUsername(@NonNull String username);

    @NonNull
    List<String> findUsernamesByPersonId(@NonNull UUID personId);

    @NonNull
    UserPersonLinkResponse findLinkByPersonId(@NonNull UUID personId);
}
