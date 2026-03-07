package com.positivity.customer.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.positivity.customer.internal.entity.PartyNote;

/**
 * Repository for persisting party note projections from inbound events.
 */
@Repository
public interface PartyNoteRepository extends JpaRepository<PartyNote, UUID> {
}
