package com.positivity.location.repository;

import com.positivity.location.entity.LocationParent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LocationParentRepository extends JpaRepository<LocationParent, Long> {
}

