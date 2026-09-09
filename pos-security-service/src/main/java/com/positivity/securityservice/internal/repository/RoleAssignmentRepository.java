package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {
    List<RoleAssignment> findByUser(User user);

    @EntityGraph(attributePaths = {"user", "role"})
    List<RoleAssignment> findAllByUser_Id(UUID userId);

    List<RoleAssignment> findByUser_IdAndRole_Id(UUID userId, UUID roleId);

    List<RoleAssignment> findByRole(Role role);

    void deleteByRole_Id(UUID roleId);

    List<RoleAssignment> findByUserAndRole(User user, Role role);

    /**
     * A user's assignments that are effective at {@code asOf}.
     *
     * <p>The window is half-open — start-inclusive, end-exclusive — matching
     * {@code RoleAssignment.isEffectiveAt}, which is the only other place that defines it.
     *
     * <p>{@code asOf} is a bound parameter rather than the database's {@code CURRENT_DATE}, which
     * is what this compared against before. Both columns are timestamps and {@code CURRENT_DATE}
     * is midnight, so both bounds were skewed by up to a day in the wrong direction: a role
     * granted at 09:00 did not take effect until the following midnight, and a role revoked at
     * 09:00 kept granting until the following midnight. That second one is the one that matters —
     * this query backs the authorities on every authenticated request through
     * {@code CustomUserDetailsService} (#1910). Binding the instant also puts the answer on the
     * service's injected {@link java.time.Clock}, so a test can place a fixture on the boundary
     * instead of a year away from it.
     *
     * <p>ArchUnit restricts this query to {@code EffectiveGrantResolverImpl} alone (#1914): every
     * other caller, listing endpoints included, reads the same rows off
     * {@code EffectiveGrantResolver.EffectiveGrants.assignments()} instead of re-querying here —
     * a duplicated query method ({@code findCurrentAssignmentsByUser}, identical to this one) that
     * existed only so listing had its own name to call was removed for exactly that reason (#1914
     * Part A).
     */
    @EntityGraph(attributePaths = {"user", "role"})
    @Query("SELECT ra FROM RoleAssignment ra WHERE ra.user = :user " + "AND ra.effectiveStartDate <= :asOf "
            + "AND (ra.effectiveEndDate IS NULL OR ra.effectiveEndDate > :asOf)")
    List<RoleAssignment> findEffectiveAssignmentsByUser(@Param("user") User user, @Param("asOf") LocalDateTime asOf);
}
