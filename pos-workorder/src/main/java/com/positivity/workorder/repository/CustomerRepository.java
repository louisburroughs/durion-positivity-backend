package com.positivity.workorder.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.workorder.entity.Customer;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
