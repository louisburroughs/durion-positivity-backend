package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtCustomerBillingRules;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCustomerBillingRulesRepository extends JpaRepository<ExtCustomerBillingRules, UUID> {}
