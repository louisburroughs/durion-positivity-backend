package com.positivity.securityservice.repository;

import com.positivity.securityservice.model.RoleAssignment;
import com.positivity.securityservice.model.User;
import com.positivity.securityservice.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, Long> {
    List<RoleAssignment> findByUser(User user);
    
    List<RoleAssignment> findByRole(Role role);
    
    List<RoleAssignment> findByUserAndRole(User user, Role role);
    
    @Query("SELECT ra FROM RoleAssignment ra WHERE ra.user = :user " +
           "AND ra.effectiveStartDate <= :date " +
           "AND (ra.effectiveEndDate IS NULL OR ra.effectiveEndDate >= :date)")
    List<RoleAssignment> findEffectiveAssignmentsByUserAndDate(
        @Param("user") User user,
        @Param("date") LocalDate date
    );
    
    @Query("SELECT ra FROM RoleAssignment ra WHERE ra.user = :user " +
           "AND ra.effectiveStartDate <= CURRENT_DATE " +
           "AND (ra.effectiveEndDate IS NULL OR ra.effectiveEndDate >= CURRENT_DATE)")
    List<RoleAssignment> findEffectiveAssignmentsByUser(@Param("user") User user);
}
