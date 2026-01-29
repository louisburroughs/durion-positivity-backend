package com.positivity.customer.internal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.AbstractCustomer;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<AbstractCustomer, Long> {
    Optional<AbstractCustomer> findByCustomerNumber(String customerNumber);
}
