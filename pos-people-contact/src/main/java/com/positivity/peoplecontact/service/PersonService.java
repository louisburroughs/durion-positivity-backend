package com.positivity.peoplecontact.service;

import com.positivity.peoplecontact.internal.dto.Person;
import com.positivity.peoplecontact.internal.dto.ResolvePersonRequest;
import com.positivity.peoplecontact.internal.dto.ResolvePersonResponse;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PersonService {

    @NonNull
    default List<Person> getAllPeople() {
        return getAllPeople(null);
    }

    @NonNull
    List<Person> getAllPeople(@Nullable String q);

    @NonNull
    Optional<Person> getPersonById(@NonNull UUID id);

    /**
     * Resolve many persons by id in one round trip. Unknown ids are omitted from
     * the result (no error). Supports cross-service identity reads where
     * pos-people-contact is the source of truth for person attributes (ADR-0015 I2).
     *
     * @param ids the person ids to fetch
     * @return persons that exist, in no guaranteed order
     */
    @NonNull
    List<Person> getPeopleByIds(@NonNull Collection<UUID> ids);

    /**
     * Replace all typed contact points for a person (pos-people-contact is the source of
     * truth for contacts, ADR-0015 I2). Existing points are removed and replaced
     * with the supplied set.
     *
     * @param personId the canonical person id
     * @param contactPoints the contact points to persist (may be empty)
     */
    void replaceContactPoints(@NonNull UUID personId, @NonNull List<Person.ContactPointDto> contactPoints);

    @NonNull
    Person savePerson(@NonNull Person person);

    @NonNull
    ResolvePersonResponse resolvePerson(@NonNull ResolvePersonRequest request);

    void deletePerson(@NonNull UUID id);
}
