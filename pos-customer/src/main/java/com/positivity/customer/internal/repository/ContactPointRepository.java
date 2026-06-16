package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ContactPoint;
import com.positivity.customer.internal.enums.ContactPointType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for ContactPoint entity operations.
 */
@Repository
public interface ContactPointRepository extends JpaRepository<ContactPoint, UUID> {

    /**
     * Find all contact points for a person.
     *
     * @param personId the person ID
     * @return list of contact points
     */
    List<ContactPoint> findByPersonPartyId(@NonNull UUID personId);

    /**
     * Find contact points by type for a person.
     *
     * @param personId    the person ID
     * @param contactType the contact type
     * @return list of matching contact points
     */
    List<ContactPoint> findByPersonPartyIdAndContactType(@NonNull UUID personId, @NonNull ContactPointType contactType);

    /**
     * Find the primary contact point of a specific type for a person.
     *
     * <p>Returns the earliest-created primary if more than one exists, tolerating
     * legacy data where a person may carry multiple primaries of the same type
     * (e.g. one from migration and one from seed). Avoids
     * {@code NonUniqueResultException}.
     *
     * @param personId    the person ID
     * @param contactType the contact type
     * @return the primary contact point, or {@code null} if none exists
     */
    ContactPoint findFirstByPersonPartyIdAndContactTypeAndIsPrimaryTrueOrderByCreatedAtAsc(
            @NonNull UUID personId, @NonNull ContactPointType contactType);

    /**
     * Check if a contact value exists for any person.
     *
     * @param value the contact value (email or phone)
     * @return true if the value exists
     */
    boolean existsByValue(@NonNull String value);
}
