package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Row locks on vendor bills that also bring the locked state into the persistence context (CAP:550 S12, #2509 review).
 * A {@code FOR UPDATE} query does not overwrite an entity the context already holds, so a bill read unlocked first (a
 * match candidate, a duplicate found by the guard) would keep its stale status under the lock and a decision taken
 * meanwhile by another transaction would be overwritten. Here a held bill is refreshed under {@code
 * PESSIMISTIC_WRITE}; a bill not yet held is read under the lock. Callers re-check the status afterwards.
 *
 * <p>Several bills are always locked in id order, so two commands locking the same bills cannot deadlock.
 */
@Component
@RequiredArgsConstructor
public class VendorBillLocks {

    private final EntityManager entityManager;
    private final VendorBillRepository bills;

    /** Locks {@code bill}'s row until the transaction ends and returns it with the state the lock saw. */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull VendorBill lock(@NonNull VendorBill bill) {
        if (entityManager.contains(bill)) {
            entityManager.refresh(bill, LockModeType.PESSIMISTIC_WRITE);
            return bill;
        }
        return bills.lockById(bill.getVendorBillId()).orElse(bill);
    }

    /** Locks the bills of {@code billIds} in id order; ids no longer visible are skipped. */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull List<VendorBill> lockAll(@NonNull Collection<UUID> billIds) {
        List<VendorBill> locked = new ArrayList<>();
        billIds.stream().distinct().sorted().forEach(id -> {
            VendorBill held = entityManager.find(VendorBill.class, id);
            if (held != null) {
                locked.add(lock(held));
            }
        });
        return locked;
    }
}
