package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ExtWorkorderPartReplica;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExtWorkorderPartReplicaRepository extends JpaRepository<ExtWorkorderPartReplica, UUID> {

    @Modifying
    void deleteByWorkorderId(UUID workorderId);

    /** A workorder's part lines (#2206), the returnable-item source of record for {@code ReturnService}. */
    List<ExtWorkorderPartReplica> findByWorkorderId(UUID workorderId);

    /** Part-line count per workorder (#2211), batched for a page of search results. */
    @Query("select p.workorderId as workorderId, count(p) as lineCount from ExtWorkorderPartReplica p "
            + "where p.workorderId in :workorderIds group by p.workorderId")
    List<WorkorderPartLineCount> countLinesByWorkorderIdIn(@Param("workorderIds") Collection<UUID> workorderIds);

    /** Projection for {@link #countLinesByWorkorderIdIn}. */
    interface WorkorderPartLineCount {
        UUID getWorkorderId();

        long getLineCount();
    }
}
