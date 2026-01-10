package com.positivity.workorder.repository;

import com.positivity.workorder.entity.ChangeRequest;
import com.positivity.workorder.entity.ChangeRequest.ChangeRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {
    List<ChangeRequest> findByWorkOrderId(Long workOrderId);
    List<ChangeRequest> findByWorkOrderIdAndStatus(Long workOrderId, ChangeRequestStatus status);
    List<ChangeRequest> findByStatus(ChangeRequestStatus status);
}
