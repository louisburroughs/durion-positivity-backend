package com.positivity.securityservice.repository;

import com.positivity.securityservice.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByName(String name);
    
    boolean existsByName(String name);
    
    List<Permission> findByDomain(String domain);
    
    List<Permission> findByDomainAndResource(String domain, String resource);
    
    @Query("SELECT p FROM Permission p WHERE p.domain = :domain AND p.resource = :resource AND p.action = :action")
    Optional<Permission> findByDomainResourceAction(
        @Param("domain") String domain,
        @Param("resource") String resource,
        @Param("action") String action
    );
    
    List<Permission> findByRegisteredByService(String serviceName);
}
