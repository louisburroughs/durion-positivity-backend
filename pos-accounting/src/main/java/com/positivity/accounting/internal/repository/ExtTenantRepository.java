package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtTenant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the tenant-locale replica; the key is the tenant's own id (#2618). */
public interface ExtTenantRepository extends JpaRepository<ExtTenant, UUID> {}
