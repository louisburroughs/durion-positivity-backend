package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BayService {

    BayResponse createBay(UUID locationId, BayRequest request);

    Page<BayResponse> listBays(UUID locationId, String status, String bayType, Pageable pageable);

    BayResponse getBay(UUID locationId, UUID bayId);

    BayResponse patchBay(UUID locationId, UUID bayId, BayPatchRequest patch);

    /**
     * Hard-delete a bay and publish the {@code location.bay.deleted} tombstone (issue #1668).
     *
     * @return {@code true} when a bay was deleted, {@code false} when none matched
     */
    boolean deleteBay(UUID locationId, UUID bayId);
}
