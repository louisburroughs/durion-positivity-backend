package com.positivity.customer.repository;

import com.positivity.customer.model.AbstractCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<AbstractCustomer, Long> {
    Optional<AbstractCustomer> findByCustomerNumber(String customerNumber);
}

