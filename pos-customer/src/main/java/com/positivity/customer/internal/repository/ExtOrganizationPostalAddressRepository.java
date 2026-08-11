package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ExtOrganizationPostalAddress;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtOrganizationPostalAddressRepository extends JpaRepository<ExtOrganizationPostalAddress, UUID> {}
