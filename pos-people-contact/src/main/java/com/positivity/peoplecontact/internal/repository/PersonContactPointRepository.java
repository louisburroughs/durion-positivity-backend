package com.positivity.peoplecontact.internal.repository;

import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for {@link PersonContactPoint}. Supports batch loading by person id
 * for the people directory and cross-service identity reads.
 */
public interface PersonContactPointRepository extends JpaRepository<PersonContactPoint, UUID> {

    List<PersonContactPoint> findByPersonId(@NonNull UUID personId);

    List<PersonContactPoint> findByPersonIdIn(@NonNull Collection<UUID> personIds);

    List<PersonContactPoint> findByPersonIdAndContactType(
            @NonNull UUID personId, @NonNull ContactPointType contactType);

    List<PersonContactPoint> findByContactTypeAndValue(@NonNull ContactPointType contactType, @NonNull String value);

    List<PersonContactPoint> findByContactTypeAndValueIgnoreCase(
            @NonNull ContactPointType contactType, @NonNull String value);

    List<PersonContactPoint> findByContactTypeAndIsPrimaryAndValueIgnoreCase(
            @NonNull ContactPointType contactType, boolean isPrimary, @NonNull String value);

    void deleteByPersonId(@NonNull UUID personId);

    @Modifying
    @Transactional
    void deleteByPersonIdAndContactType(@NonNull UUID personId, @NonNull ContactPointType contactType);
}
