package com.positivity.people.service;

import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface UserPersonLinkService {

	boolean linkExistsByUserId(@NonNull UUID userId);

	boolean linkExistsByUserIdAndPersonId(@NonNull UUID userId, @NonNull UUID personId);

	@NonNull UserPersonLinkResponse createUserLink(@NonNull UUID userId, @NonNull UUID personId);

	@NonNull List<UserPersonLinkResponse> getUserLinks(@NonNull UUID personId);

	@NonNull UserPersonLinkResponse linkUserToPerson(@NonNull LinkUserToPersonRequest request);

	void unlinkUserFromPerson(@NonNull UUID userId);

	@NonNull PersonResponse findPersonByUserId(@NonNull UUID userId);

	@NonNull List<UUID> findUserIdsByPersonId(@NonNull UUID personId);

	@NonNull UserPersonLinkResponse findLinkByPersonId(@NonNull UUID personId);

}
