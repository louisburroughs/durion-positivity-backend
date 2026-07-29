package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.CustomerInteraction;
import com.positivity.customer.internal.enums.InteractionType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerInteractionRepository extends JpaRepository<CustomerInteraction, UUID> {

    Page<CustomerInteraction> findByPartyIdOrderByOccurredAtDesc(UUID partyId, Pageable pageable);

    Page<CustomerInteraction> findByPartyIdAndTypeOrderByOccurredAtDesc(
            UUID partyId, InteractionType type, Pageable pageable);

    List<CustomerInteraction> findTop5ByPartyIdOrderByOccurredAtDesc(UUID partyId);

    boolean existsBySourceEventId(String sourceEventId);
}
