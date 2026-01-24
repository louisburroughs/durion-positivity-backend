package com.positivity.customer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.entity.Party;

import java.util.Optional;

@Repository
public interface PartyRepository extends JpaRepository<Party, Long> {
    Optional<Party> findByPartyNumber(String partyNumber);
}
