package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.ChangeRequest.ChangeRequestStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChangeRequestRepository extends JpaRepository<ChangeRequest, UUID> {
    List<ChangeRequest> findByWorkorder_Id(UUID workorderId);

    List<ChangeRequest> findByWorkorder_IdAndStatus(UUID workorderId, ChangeRequestStatus status);

    List<ChangeRequest> findByStatus(ChangeRequestStatus status);
}
