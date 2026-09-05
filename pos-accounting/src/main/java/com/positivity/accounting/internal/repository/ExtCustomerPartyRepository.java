package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtCustomerParty;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Read access to the {@code ext_customer_party} replica (issue #1779). Batch display resolution
 * uses the inherited {@code findAllById}, which issues a single {@code IN} query — list responses
 * must not resolve names one row at a time.
 */
public interface ExtCustomerPartyRepository extends JpaRepository<ExtCustomerParty, UUID> {}
