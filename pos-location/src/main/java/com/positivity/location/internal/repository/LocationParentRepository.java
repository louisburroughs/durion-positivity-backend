package com.positivity.location.internal.repository;

import com.positivity.location.internal.entity.LocationParent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocationParentRepository extends JpaRepository<LocationParent, Long> {
}

