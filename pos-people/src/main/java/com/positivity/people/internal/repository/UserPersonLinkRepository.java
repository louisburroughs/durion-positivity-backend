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
     * <p>Employment status now lives on the Employee table, so the inactive filter joins
     * Employee by person id. The person is JOIN FETCHed so the response mapping does not
     * trigger per-row lazy loads (N+1); the caller resolves employment status/effective-at
     * from the Employee table.
     *
     * @param status         the link status to match (ACTIVE)
     * @param personStatuses the employment statuses considered inactive
     * @return offending links (active user, inactive employee)
     */
    @Query("SELECT l FROM UserPersonLink l JOIN FETCH l.person p, Employee e "
            + "WHERE e.personId = p.id AND l.status = :status AND e.status IN :personStatuses")
    List<UserPersonLink> findActiveLinksForInactiveEmployees(
            @Param("status") @NonNull UserLinkStatus status,
            @Param("personStatuses") @NonNull Collection<EmployeeStatus> personStatuses);

    List<UserPersonLink> findByPerson_Id(@NonNull UUID personId);

    List<UserPersonLink> findByPerson_IdIn(@NonNull Collection<UUID> personIds);

    Optional<UserPersonLink> findByPerson_IdAndStatus(@NonNull UUID personId, @NonNull UserLinkStatus status);

    Optional<UserPersonLink> findFirstByPerson_IdAndStatusOrderByCreatedAtDesc(
            @NonNull UUID personId, @NonNull UserLinkStatus status);

    boolean existsByUserId(@NonNull UUID userId);

    boolean existsByUserIdAndPerson_Id(@NonNull UUID userId, @NonNull UUID personId);

    void deleteByUserId(@NonNull UUID userId);
}
