package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.ExtBillingRules;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtBillingRulesRepository extends JpaRepository<ExtBillingRules, UUID> {}
