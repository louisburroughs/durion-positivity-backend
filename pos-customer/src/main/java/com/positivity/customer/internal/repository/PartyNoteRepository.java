package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PartyNote;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for persisting party note projections from inbound events.
 */
@Repository
public interface PartyNoteRepository extends JpaRepository<PartyNote, UUID> {}
