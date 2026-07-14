package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.ExtCustomerPersonIdentity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCustomerPersonIdentityRepository extends JpaRepository<ExtCustomerPersonIdentity, UUID> {}
