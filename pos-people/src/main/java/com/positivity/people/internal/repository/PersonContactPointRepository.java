package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.PersonContactPoint;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for {@link PersonContactPoint}. Supports batch loading by person id
 * for the people directory and cross-service identity reads.
 */
@Repository
public interface PersonContactPointRepository extends JpaRepository<PersonContactPoint, UUID> {

    List<PersonContactPoint> findByPersonId(@NonNull UUID personId);

    List<PersonContactPoint> findByPersonIdIn(@NonNull Collection<UUID> personIds);

    void deleteByPersonId(@NonNull UUID personId);
}
