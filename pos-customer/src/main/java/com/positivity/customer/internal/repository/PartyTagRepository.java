package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.PartyTag;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PartyTagRepository extends JpaRepository<PartyTag, UUID> {

    Optional<PartyTag> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<PartyTag> findAllByActiveTrueOrderByNameAsc();

    List<PartyTag> findAllByOrderByNameAsc();

    List<PartyTag> findAllByTagIdIn(List<UUID> tagIds);
}
