package com.positivity.customer.internal.repository;

import org.springframework.stereotype.Repository;

/**
 * Backward-compatibility alias for CommercialPartyRepository.
 * 
 * @deprecated Use CommercialPartyRepository instead
 */
@Deprecated(since = "CAP:091", forRemoval = false)
@Repository
public interface PartyRepository extends CommercialPartyRepository {
}
