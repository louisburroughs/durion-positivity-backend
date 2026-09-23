package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Employee;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByPersonId(UUID personId);

    Optional<Employee> findByEmployeeNumberIgnoreCase(String employeeNumber);

    List<Employee> findByPersonIdIn(Collection<UUID> personIds);

    boolean existsByEmployeeNumberIgnoreCase(String employeeNumber);

    boolean existsByEmployeeNumberIgnoreCaseAndPersonIdNot(String employeeNumber, UUID personId);

    /**
     * Atomically reactivates a DISABLED employee to ACTIVE, folding the state guard and the
     * optimistic-concurrency token check into the same conditional UPDATE (#2158 finding C).
     *
     * <p>The previous shape of {@code enableEmployee} loaded the row, compared {@code
     * request.updatedAt} against the loaded entity in memory, and only then called {@code save()}
     * — two concurrent requests that both load the same DISABLED row with the same {@code
     * updatedAt} both pass that in-memory check and both save, the second silently clobbering the
     * first's write (and its published fact) rather than 409ing. Putting the status and token
     * checks in the UPDATE's {@code WHERE} clause closes that gap: the database evaluates the
     * predicate row-locked as part of the single statement, so only one of two racing callers can
     * still see a matching row by the time it writes; the other updates zero rows.
     *
     * <p>{@code @Version} (a JPA-managed optimistic-lock column) would give the same atomicity,
     * but only by adding a schema column {@code Employee} does not have today — this conditional
     * update needs no migration, reusing the {@code updated_at} column already on the table as
     * the token, exactly as {@code enableEmployee} already treated it. This is a JPQL bulk
     * statement, so it bypasses Hibernate's entity lifecycle: {@code updated_at} is normally
     * maintained by {@code @LastModifiedDate}/{@code AuditingEntityListener}, which only fires on
     * a managed-entity {@code save()}, not on a bulk update — so the query sets {@code updated_at}
     * to {@code now} itself rather than relying on auditing to do it.
     *
     * @return 1 if the row was DISABLED with {@code token} still current and is now ACTIVE with
     *     {@code now} as its new {@code updatedAt}; 0 otherwise. A 0 does not by itself say
     *     whether the row was no longer DISABLED or the token no longer matched — the caller
     *     cannot distinguish those from this return value alone, and does not need to: both mean
     *     "the record changed since it was read" and map to the same 409.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Employee e set e.status = com.positivity.people.internal.enums.EmployeeStatus.ACTIVE, "
            + "e.statusEffectiveAt = :now, e.updatedAt = :now "
            + "where e.personId = :personId "
            + "and e.status = com.positivity.people.internal.enums.EmployeeStatus.DISABLED "
            + "and e.updatedAt = :token")
    int reactivateIfDisabledAndTokenMatches(
            @Param("personId") UUID personId, @Param("token") Instant token, @Param("now") Instant now);
}
