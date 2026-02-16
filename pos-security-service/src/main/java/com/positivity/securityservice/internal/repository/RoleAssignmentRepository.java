package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {
    List<RoleAssignment> findByUser(User user);

    List<RoleAssignment> findAllByUser_Id(UUID userId);

    List<RoleAssignment> findByUser_IdAndRole_IdAndScopeType(UUID userId, UUID roleId, ScopeType scopeType);

    List<RoleAssignment> findByRole(Role role);

    List<RoleAssignment> findByUserAndRole(User user, Role role);

    @Query("SELECT ra FROM RoleAssignment ra WHERE ra.user = :user " +
            "AND ra.effectiveStartDate <= :date " +
            "AND (ra.effectiveEndDate IS NULL OR ra.effectiveEndDate >= :date)")
    List<RoleAssignment> findEffectiveAssignmentsByUserAndDate(
            @Param("user") User user,
            @Param("date") LocalDate date);

    @Query("SELECT ra FROM RoleAssignment ra WHERE ra.user = :user " +
            "AND ra.effectiveStartDate <= CURRENT_DATE " +
            "AND (ra.effectiveEndDate IS NULL OR ra.effectiveEndDate >= CURRENT_DATE)")
    List<RoleAssignment> findEffectiveAssignmentsByUser(@Param("user") User user);

    @Query("SELECT ra FROM RoleAssignment ra WHERE ra.user.id = :userId " +
            "AND ra.effectiveStartDate <= CURRENT_DATE " +
            "AND (ra.effectiveEndDate IS NULL OR ra.effectiveEndDate >= CURRENT_DATE)")
    List<RoleAssignment> findEffectiveAssignmentsByUser_Id(@Param("userId") UUID userId);
}
