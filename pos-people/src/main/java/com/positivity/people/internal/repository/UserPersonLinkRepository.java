package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.enums.UserLinkStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPersonLinkRepository extends JpaRepository<UserPersonLink, UUID> {

    Optional<UserPersonLink> findByUserId(@NonNull UUID userId);

    /**
     * Compliance check (ADR-0015 §4): links whose user is still ACTIVE but whose
     * linked person sits in an inactive status. These represent users that should
     * have been disabled when the person was disabled/archived.
     *
     * <p>Fetches the person in the same query (JOIN FETCH) so the response mapping
     * does not trigger per-row lazy loads (N+1).
     *
     * @param status         the link status to match (ACTIVE)
     * @param personStatuses the person statuses considered inactive
     * @return offending links (active user, inactive person)
     */
    @Query("SELECT l FROM UserPersonLink l JOIN FETCH l.person p "
            + "WHERE l.status = :status AND p.status IN :personStatuses")
    List<UserPersonLink> findByStatusAndPerson_StatusIn(
            @Param("status") @NonNull UserLinkStatus status,
            @Param("personStatuses") @NonNull Collection<EmployeeStatus> personStatuses);

    List<UserPersonLink> findByPerson_Id(@NonNull UUID personId);

    Optional<UserPersonLink> findByPerson_IdAndStatus(@NonNull UUID personId, @NonNull UserLinkStatus status);

    Optional<UserPersonLink> findFirstByPerson_IdAndStatusOrderByCreatedAtDesc(
            @NonNull UUID personId, @NonNull UserLinkStatus status);

    boolean existsByUserId(@NonNull UUID userId);

    boolean existsByUserIdAndPerson_Id(@NonNull UUID userId, @NonNull UUID personId);

    void deleteByUserId(@NonNull UUID userId);
}
