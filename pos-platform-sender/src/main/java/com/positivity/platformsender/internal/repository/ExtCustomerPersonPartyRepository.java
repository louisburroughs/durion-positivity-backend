package com.positivity.platformsender.internal.repository;

import com.positivity.platformsender.internal.entity.ExtCustomerPersonParty;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtCustomerPersonPartyRepository extends JpaRepository<ExtCustomerPersonParty, UUID> {}
