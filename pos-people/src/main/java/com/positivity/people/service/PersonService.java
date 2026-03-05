package com.positivity.people.service;

import com.positivity.people.internal.dto.Person;
import com.positivity.people.internal.dto.ResolvePersonRequest;
import com.positivity.people.internal.dto.ResolvePersonResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface PersonService {

	@NonNull List<Person> getAllPeople();

	@NonNull Optional<Person> getPersonById(@NonNull UUID id);

	@NonNull Person savePerson(@NonNull Person person);

	@NonNull ResolvePersonResponse resolvePerson(@NonNull ResolvePersonRequest request);

	void deletePerson(@NonNull UUID id);

}
