package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.Role;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, UUID> {
    Optional<Role> findByName(String name);

    boolean existsByName(String name);

    /** Story #62: case-insensitive duplicate check for role name uniqueness. */
    boolean existsByNameIgnoreCase(String name);
}
