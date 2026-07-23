package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtCustomer;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCustomerRepository extends JpaRepository<ExtCustomer, UUID> {}
