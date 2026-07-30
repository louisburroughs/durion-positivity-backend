package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.ConsentEvent;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsentEventRepository extends JpaRepository<ConsentEvent, UUID> {

    Page<ConsentEvent> findByPartyIdOrderByOccurredAtDesc(UUID partyId, Pageable pageable);
}
