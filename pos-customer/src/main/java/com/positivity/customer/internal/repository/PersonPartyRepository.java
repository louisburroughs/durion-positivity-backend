package com.positivity.customer.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.PersonParty;

/**
 * Repository for PersonParty entities (CAP:091 Story #104).
 */
@Repository
public interface PersonPartyRepository extends JpaRepository<PersonParty, UUID> {
}
