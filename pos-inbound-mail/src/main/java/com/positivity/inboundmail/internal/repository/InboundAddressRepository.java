package com.positivity.inboundmail.internal.repository;

import com.positivity.inboundmail.internal.entity.InboundAddress;
import com.positivity.inboundmail.internal.enums.AddressStatus;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundAddressRepository extends JpaRepository<InboundAddress, UUID> {

    /** The tenant's one live address, if it has one. */
    @NonNull
    Optional<InboundAddress> findByTenantIdAndStatus(@NonNull UUID tenantId, @NonNull AddressStatus status);

    /** The address a rotation request issued, whatever has become of it since. */
    @NonNull
    Optional<InboundAddress> findByTenantIdAndIssuedByRequestId(@NonNull UUID tenantId, @NonNull UUID requestId);

    /** Resolution for the receive path (Part B): exact, lower-case. */
    @NonNull
    Optional<InboundAddress> findByAddress(@NonNull String address);
}
