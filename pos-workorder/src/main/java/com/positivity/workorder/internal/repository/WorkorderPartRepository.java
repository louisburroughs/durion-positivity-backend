package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderPart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkorderPartRepository extends JpaRepository<WorkorderPart, Long> {
    List<WorkorderPart> findByChangeRequestId(Long changeRequestId);
}

