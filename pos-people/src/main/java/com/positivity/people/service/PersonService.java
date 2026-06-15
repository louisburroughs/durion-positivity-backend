package com.positivity.people.service;

import com.positivity.people.internal.dto.Person;
import com.positivity.people.internal.dto.ResolvePersonRequest;
import com.positivity.people.internal.dto.ResolvePersonResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PersonService {

    @NonNull
    default List<Person> getAllPeople() {
        return getAllPeople(null, null);
    }

    @NonNull
    List<Person> getAllPeople(@Nullable String type, @Nullable String q);

    @NonNull
    Optional<Person> getPersonById(@NonNull UUID id);

    @NonNull
    Person savePerson(@NonNull Person person);

    @NonNull
    ResolvePersonResponse resolvePerson(@NonNull ResolvePersonRequest request);

    void deletePerson(@NonNull UUID id);
}
