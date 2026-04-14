package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WorkorderStateTransitionRepository extends JpaRepository<WorkorderStateTransition, UUID> {
    @NonNull
    List<WorkorderStateTransition> findByWorkorder_Id(@NonNull UUID workorderId);

    @NonNull
    List<WorkorderStateTransition> findByWorkorder_IdOrderByTransitionedAtDesc(@NonNull UUID workorderId);
}
