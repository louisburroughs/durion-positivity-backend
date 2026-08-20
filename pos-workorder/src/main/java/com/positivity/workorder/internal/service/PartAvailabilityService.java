package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.entity.ExtInventoryAvailabilityReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.repository.ExtInventoryAvailabilityReplicaRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Answers "is this part really on the shelf at this shop" from the owned-availability replica
 * (CAP #1315). Before this, {@code WorkorderPartUsageServiceImpl} issued parts without asking
 * anything — its own class comment said so.
 *
 * <h2>Absent means unknown, and unknown reports short</h2>
 *
 * A missing replica row is not evidence of stock. Reporting available on absence would let every
 * item pos-inventory has never emitted a fact for pass the gate silently, which is the same
 * always-yes behaviour the gate exists to end.
 *
 * <p>The cost of that pessimism is real here — a short answer blocks a part issue rather than
 * admitting a backorder — but it is the recoverable direction. A blocked issue is visible
 * immediately and the advisor can order, transfer, or substitute; a part recorded as issued that
 * was never on the shelf corrupts every quantity downstream of it and nothing surfaces the error.
 *
 * <h2>Site scope</h2>
 *
 * Availability is measured at the servicing site alone ({@code workorder.shopId}, which holds a
 * Location id — {@code WorkorderServiceImpl} populates {@code shopId} and {@code locationId} from
 * the same resolved location). Stock at a sibling site is not available to this job until someone
 * transfers it, and counting it would promise parts no one has committed to move.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartAvailabilityService {

    private final ExtInventoryAvailabilityReplicaRepository availabilityRepository;

    /**
     * Owned ATP for a part at its workorder's servicing site, or zero when nothing is known.
     *
     * @param workorder the job the part belongs to; supplies the servicing site
     * @param part the part line being asked about
     */
    public BigDecimal availableFor(@NonNull Workorder workorder, @NonNull WorkorderPart part) {
        UUID locationId = workorder.getShopId();
        UUID stockItemId = part.getProductEntityId();
        if (locationId == null || stockItemId == null) {
            // A non-inventory part has no productEntityId and no owned stock to measure; a
            // workorder with no site has nowhere to measure it at. Both report short rather than
            // guessing, and the caller decides whether the gate applies at all.
            return BigDecimal.ZERO;
        }
        return lookup(stockItemId, locationId)
                .map(ExtInventoryAvailabilityReplica::getAvailableToPromiseQuantity)
                .map(atp -> atp.max(BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Whether owned ATP covers the requested quantity.
     *
     * <p>A part carrying no {@code productEntityId} is not inventory — labour, shop supplies, a
     * non-stocked item — and there is nothing for this gate to measure. Those are covered by
     * definition rather than blocked, because blocking them would stop jobs on items the owner
     * never tracked.
     */
    public boolean covers(@NonNull Workorder workorder, @NonNull WorkorderPart part, @NonNull BigDecimal quantity) {
        if (part.getProductEntityId() == null) {
            return true;
        }
        return availableFor(workorder, part).compareTo(quantity) >= 0;
    }

    /**
     * pos-inventory's ledger disagrees with itself about what {@code stockItemId} holds — the
     * allocation path writes a product UUID as a string, receiving paths write the human SKU — and
     * availability facts inherit whichever the ledger held. This module only knows the product
     * UUID, so that is the key it uses; a part stocked exclusively under a SKU key reports short
     * and is corrected by the reservation outcome, rather than being silently passed.
     */
    private Optional<ExtInventoryAvailabilityReplica> lookup(@NonNull UUID stockItemId, @Nullable UUID locationId) {
        return availabilityRepository.findByStockItemIdAndLocationId(stockItemId.toString(), locationId);
    }
}
