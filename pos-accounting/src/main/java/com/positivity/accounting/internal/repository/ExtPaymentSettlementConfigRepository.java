package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtPaymentSettlementConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for the per-provider settlement matching-config replica (story F1c, decision D-9). */
public interface ExtPaymentSettlementConfigRepository extends JpaRepository<ExtPaymentSettlementConfig, String> {}
