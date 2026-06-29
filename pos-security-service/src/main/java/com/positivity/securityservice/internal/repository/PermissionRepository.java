package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.Permission;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    Optional<Permission> findByName(String name);

    Optional<Permission> findByBitIndex(int bitIndex);

    boolean existsByName(String name);

    List<Permission> findByDomain(String domain);

    Page<Permission> findByDomain(String domain, Pageable pageable);

    List<Permission> findByDomainAndResource(String domain, String resource);

    @Query("SELECT p FROM Permission p WHERE p.domain = :domain AND p.resource = :resource AND p.action = :action")
    Optional<Permission> findByDomainResourceAction(
            @Param("domain") String domain, @Param("resource") String resource, @Param("action") String action);

    List<Permission> findByRegisteredByService(String serviceName);
}
