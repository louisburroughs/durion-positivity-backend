package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.SubstitutionGroupEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubstitutionGroupRepository extends JpaRepository<SubstitutionGroupEntity, UUID> {}
