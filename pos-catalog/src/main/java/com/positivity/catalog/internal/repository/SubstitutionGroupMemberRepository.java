package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SubstitutionGroupMemberEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubstitutionGroupMemberRepository extends JpaRepository<SubstitutionGroupMemberEntity, UUID> {

    Optional<SubstitutionGroupMemberEntity> findByProductId(UUID productId);

    Optional<SubstitutionGroupMemberEntity> findByGroupIdAndProductId(UUID groupId, UUID productId);

    List<SubstitutionGroupMemberEntity> findByGroupIdOrderByCreatedAtAsc(UUID groupId);
}
