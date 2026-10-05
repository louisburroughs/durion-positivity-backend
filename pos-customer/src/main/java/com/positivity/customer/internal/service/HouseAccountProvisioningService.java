package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.enums.HouseAccountKind;
import com.positivity.customer.internal.enums.LifecycleStage;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the bound tenant's CASH house account (CAP:550 S7, #2505; accounting workspace spec
 * §4.4 item 2, decisions AW12 and AW13) — the transactional half of {@link HouseAccountProvisioner}.
 *
 * <p>Its own bean so the sweep's per-tenant call crosses the transaction proxy: one transaction
 * per tenant, holding the insert and the {@code customer.party.updated} outbox row together. A
 * second instance racing on the same tenant loses on the partial unique index
 * {@code commercial_party_house_account_uk}; its transaction rolls back, taking its fact with it.
 *
 * <p>This is the only writer of {@code CommercialParty.houseAccount}. The account carries no
 * personal data: no tax id, address, billing rules, contacts, relationships, vehicles, tags,
 * consents or communication preferences, and marketing is gated off at the account level.
 * Go-live only (AW13): provisioning creates the account and reassigns nothing to it.
 */
@Service
@RequiredArgsConstructor
public class HouseAccountProvisioningService {

    /** Customer number of the CASH account: outside the generated {@code CUST-} range, so it cannot collide. */
    public static final String CASH_CUSTOMER_NUMBER = "CASH";

    /**
     * Stored legal and display name. Consumers show their own localised label from the
     * {@code houseAccount} flag, never this name, and never identify the account by it.
     */
    public static final String CASH_ACCOUNT_NAME = "Walk-in customer";

    /** Recorded as the assigner of the fixed tier. */
    static final String SYSTEM_ACTOR = "pos-customer";

    private final Clock clock;
    private final CommercialPartyRepository commercialPartyRepository;
    private final CustomerFactPublisher customerFactPublisher;

    /**
     * Create the bound tenant's CASH house account unless it already exists.
     *
     * @return the new party id, or empty when the tenant was already provisioned
     * @throws org.springframework.dao.DataIntegrityViolationException when another instance
     *     provisioned the tenant concurrently (the unique index refused this insert)
     */
    @Transactional
    public @NonNull Optional<UUID> createCashAccountIfMissing() {
        if (cashAccountExists()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        CommercialParty account = new CommercialParty();
        account.setHouseAccount(HouseAccountKind.CASH_SALE);
        account.setLegalName(CASH_ACCOUNT_NAME);
        account.setDisplayName(CASH_ACCOUNT_NAME);
        account.setCustomerNumber(CASH_CUSTOMER_NUMBER);
        account.setPartyType(PartyType.COMMERCIAL);
        account.setStatus(AccountStatus.ACTIVE);
        account.setLifecycleStage(LifecycleStage.ACTIVE);
        // A manual override on STANDARD: tier resolution never re-tiers it.
        account.setTier(AccountTier.STANDARD);
        account.setTierManualOverride(true);
        account.setTierAssignedAt(now);
        account.setTierAssignedBy(SYSTEM_ACTOR);
        account.setAccountMarketingOptOut(true);
        account.setAccountMarketingOptOutAt(now);

        // Flushed here so a lost race surfaces as a translated DataIntegrityViolationException
        // from the repository, before any fact is queued.
        CommercialParty saved = commercialPartyRepository.saveAndFlush(account);
        customerFactPublisher.partyChanged(saved);
        return Optional.of(saved.getPartyId());
    }

    /** Whether the bound tenant already has its CASH house account. */
    @Transactional(readOnly = true)
    public boolean cashAccountExists() {
        return commercialPartyRepository
                .findByHouseAccount(HouseAccountKind.CASH_SALE)
                .isPresent();
    }
}
