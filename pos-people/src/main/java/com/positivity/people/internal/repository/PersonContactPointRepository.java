package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.PersonContactPoint;
import com.positivity.people.internal.enums.ContactPointType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for {@link PersonContactPoint}. Supports batch loading by person id
 * for the people directory and cross-service identity reads.
 */
@Repository
public interface PersonContactPointRepository extends JpaRepository<PersonContactPoint, UUID> {

    List<PersonContactPoint> findByPersonId(@NonNull UUID personId);

    List<PersonContactPoint> findByPersonIdIn(@NonNull Collection<UUID> personIds);

    List<PersonContactPoint> findByPersonIdAndContactType(
            @NonNull UUID personId, @NonNull ContactPointType contactType);

    List<PersonContactPoint> findByContactTypeAndValue(@NonNull ContactPointType contactType, @NonNull String value);

    void deleteByPersonId(@NonNull UUID personId);

    @Modifying
    @Transactional
    void deleteByPersonIdAndContactType(@NonNull UUID personId, @NonNull ContactPointType contactType);
}
