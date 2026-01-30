package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.ChangeRequest.ChangeRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, Long> {
    List<ChangeRequest> findByWorkorderId(Long workorderId);
    List<ChangeRequest> findByWorkorderIdAndStatus(Long workorderId, ChangeRequestStatus status);
    List<ChangeRequest> findByStatus(ChangeRequestStatus status);
}
