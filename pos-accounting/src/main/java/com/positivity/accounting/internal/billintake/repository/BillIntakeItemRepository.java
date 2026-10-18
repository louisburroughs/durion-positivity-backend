package com.positivity.accounting.internal.billintake.repository;

import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Bill drafts (CAP:550 S25, #2518). Read and written only by the bill-intake services. */
public interface BillIntakeItemRepository extends JpaRepository<BillIntakeItem, UUID> {

    /** Drafts in a status: the {@code accounting.bill_intake.items} gauge and the lists of the later parts. */
    long countByStatus(@NonNull BillIntakeStatus status);
}
