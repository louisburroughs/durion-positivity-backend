package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.PrincipalRole;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PrincipalRoleRepository extends JpaRepository<PrincipalRole, UUID> {

    boolean existsByPrincipalIdAndRole_Id(String principalId, UUID roleId);

    List<PrincipalRole> findByPrincipalId(String principalId);
}
