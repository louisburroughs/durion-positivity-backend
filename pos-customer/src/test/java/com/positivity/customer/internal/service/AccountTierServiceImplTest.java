package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.dto.ResolveAccountTierRequest;
import com.positivity.customer.internal.dto.ResolveAccountTierResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AccountTierServiceImpl}.
 *
 * <p>
 * Tier drives pricing and service level, so the calculation is worth pinning at
 * its boundaries rather than at comfortable mid-band values: every revenue
 * threshold is inclusive, and an account one cent below a threshold must land in
 * the lower tier.
 *
 * <p>
 * The three inputs compose in one direction only. Contract count and account age
 * can <em>raise</em> the revenue-derived tier but never lower it — the
 * calculation guards each upgrade on {@code tier.ordinal()}, so a high-revenue
 * account with few contracts keeps its revenue tier. Getting that backwards
 * would silently demote enterprise accounts.
 *
 * <p>
 * Manual override is the other load-bearing rule: an operator-set tier survives
 * recalculation unless the caller explicitly forces it, and a forced
 * recalculation clears the override flag rather than leaving it to re-block the
 * next run.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountTierServiceImpl — tier calculation and assignment")
class AccountTierServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Mock
    private CommercialPartyRepository commercialPartyRepository;

    @Mock
    private CustomerFactPublisher customerFactPublisher;

    private AccountTierServiceImpl sut;

    @BeforeEach
    void setUp() {
        sut = new AccountTierServiceImpl(
                Clock.fixed(NOW, ZoneOffset.UTC), commercialPartyRepository, customerFactPublisher);
    }

    private static CommercialParty party(AccountTier tier, boolean manualOverride) {
        CommercialParty party = new CommercialParty();
        party.setPartyId(ACCOUNT_ID);
        party.setTier(tier);
        party.setTierManualOverride(manualOverride);
        return party;
    }

    private static ResolveAccountTierRequest.ResolveAccountTierRequestBuilder request() {
        return ResolveAccountTierRequest.builder().accountId(ACCOUNT_ID.toString());
    }

    private ResolveAccountTierResponse resolve(ResolveAccountTierRequest request) {
        return sut.resolveAccountTier(request);
    }

    @Nested
    @DisplayName("getAccountTier")
    class GetAccountTier {

        @Test
        @DisplayName("returns the stored tier and its assignment metadata")
        void get_returnsStoredTier() {
            CommercialParty party = party(AccountTier.GOLD, true);
            party.setTierAssignedAt(NOW);
            party.setTierAssignedBy("operator");
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(party));

            var response = sut.getAccountTier(ACCOUNT_ID);

            assertThat(response.getTier()).isEqualTo(AccountTier.GOLD);
            assertThat(response.getTierAssignedBy()).isEqualTo("operator");
            assertThat(response.isManualOverride()).isTrue();
        }

        @Test
        @DisplayName("reports STANDARD when the account has no tier set")
        void get_whenTierNull_defaultsToStandard() {
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(party(null, false)));

            assertThat(sut.getAccountTier(ACCOUNT_ID).getTier()).isEqualTo(AccountTier.STANDARD);
        }

        @Test
        @DisplayName("rejects an unknown account")
        void get_whenAbsent_throws() {
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> sut.getAccountTier(ACCOUNT_ID))
                    .withMessageContaining("Account not found");
        }
    }

    @Nested
    @DisplayName("revenue thresholds")
    class RevenueThresholds {

        @ParameterizedTest(name = "revenue {0} resolves to {1} with score {2}")
        @CsvSource({
            // Each threshold is inclusive; the value one cent below falls to the band under it.
            "0,        STANDARD,   0",
            "49999.99, STANDARD,   0",
            "50000,    BRONZE,     200",
            "99999.99, BRONZE,     200",
            "100000,   SILVER,     300",
            "249999.99, SILVER,    300",
            "250000,   GOLD,       400",
            "499999.99, GOLD,      400",
            "500000,   PLATINUM,   500",
            "999999.99, PLATINUM,  500",
            "1000000,  ENTERPRISE, 600",
            "9999999,  ENTERPRISE, 600",
        })
        @DisplayName("maps revenue onto tiers at inclusive boundaries")
        void revenue_mapsToTier(BigDecimal revenue, AccountTier expected, int expectedScore) {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            ResolveAccountTierResponse response =
                    resolve(request().annualRevenue(revenue).build());

            assertThat(response.getRecommendedTier()).isEqualTo(expected);
            assertThat(response.getTierScore()).isEqualTo(expectedScore);
        }

        @Test
        @DisplayName("falls back to STANDARD with a default reason when no criteria are supplied")
        void noCriteria_yieldsStandardWithDefaultReason() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            ResolveAccountTierResponse response = resolve(request().build());

            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.STANDARD);
            assertThat(response.getResolutionReason()).isEqualTo("Default tier assignment.");
            assertThat(response.getTierScore()).isZero();
        }
    }

    @Nested
    @DisplayName("contract and age upgrades")
    class Upgrades {

        @ParameterizedTest(name = "{0} contracts alone resolves to {1}")
        @CsvSource({"0, STANDARD", "1, STANDARD", "2, SILVER", "3, GOLD", "4, GOLD", "5, PLATINUM", "9, PLATINUM"})
        @DisplayName("upgrades on active contract count alone")
        void contracts_upgradeTier(int contracts, AccountTier expected) {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            assertThat(resolve(request().activeContractCount(contracts).build()).getRecommendedTier())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("a contract count can raise the revenue-derived tier")
        void contracts_canRaiseRevenueTier() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            // BRONZE on revenue, five contracts lifts it to PLATINUM.
            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("60000"))
                    .activeContractCount(5)
                    .build());

            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.PLATINUM);
            assertThat(response.getTierScore()).isEqualTo(500);
        }

        @Test
        @DisplayName("a contract count never lowers the revenue-derived tier")
        void contracts_neverLowerRevenueTier() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            // ENTERPRISE on revenue with only two contracts must stay ENTERPRISE;
            // demoting a high-revenue account here would be the costly failure.
            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("2000000"))
                    .activeContractCount(2)
                    .build());

            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.ENTERPRISE);
            assertThat(response.getTierScore()).isEqualTo(600);
        }

        @Test
        @DisplayName("a qualifying contract count is a no-op when the revenue tier already meets it")
        void contracts_noOpWhenRevenueTierAlreadyMeetsThem() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            // ENTERPRISE revenue plus 5 contracts: the contract rule qualifies for PLATINUM but
            // the account already stands higher, so neither tier, score, nor reason may change —
            // "5+ active contracts" appearing in the reason would misattribute the tier.
            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("2000000"))
                    .activeContractCount(5)
                    .build());

            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.ENTERPRISE);
            assertThat(response.getTierScore()).isEqualTo(600);
            assertThat(response.getResolutionReason()).doesNotContain("contracts");
        }

        @Test
        @DisplayName("the gold contract rule is likewise a no-op at an equal revenue tier")
        void contracts_goldRuleNoOpAtEqualTier() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("500000"))
                    .activeContractCount(3)
                    .build());

            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.PLATINUM);
            assertThat(response.getResolutionReason()).doesNotContain("contracts");
        }

        @ParameterizedTest(name = "an age of {0} months alone resolves to {1}")
        @CsvSource({"0, STANDARD", "2, STANDARD", "3, BRONZE", "48, BRONZE"})
        @DisplayName("upgrades an otherwise STANDARD account once it is established")
        void age_upgradesStandardOnly(int ageMonths, AccountTier expected) {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            assertThat(resolve(request().accountAgeMonths(ageMonths).build()).getRecommendedTier())
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("account age never lowers a tier already earned on revenue")
        void age_doesNotLowerEarnedTier() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("300000"))
                    .accountAgeMonths(120)
                    .build());

            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.GOLD);
        }

        @Test
        @DisplayName("records every rule that contributed to the outcome")
        void reason_recordsContributingRules() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("60000"))
                    .activeContractCount(5)
                    .build());

            assertThat(response.getResolutionReason())
                    .contains("Annual revenue >= $50K")
                    .contains("5+ active contracts");
        }
    }

    @Nested
    @DisplayName("applying the resolved tier")
    class ApplyTier {

        @Test
        @DisplayName("does not write when the caller only asked for a recommendation")
        void apply_whenNotRequested_writesNothing() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.STANDARD, false)));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("600000"))
                    .applyTier(false)
                    .build());

            assertThat(response.isTierApplied()).isFalse();
            verify(commercialPartyRepository, never()).save(any());
            verify(customerFactPublisher, never()).partyChanged(any());
        }

        @Test
        @DisplayName("writes the tier, stamps the assignment, and publishes the change")
        void apply_whenRequested_writesAndPublishes() {
            CommercialParty party = party(AccountTier.STANDARD, false);
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(party));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("600000"))
                    .applyTier(true)
                    .build());

            assertThat(response.isTierApplied()).isTrue();
            assertThat(party.getTier()).isEqualTo(AccountTier.PLATINUM);
            assertThat(party.getTierAssignedAt()).isEqualTo(NOW);
            assertThat(party.getTierAssignedBy()).isEqualTo("SYSTEM");
            verify(commercialPartyRepository).save(party);
            verify(customerFactPublisher).partyChanged(party);
        }

        @Test
        @DisplayName("leaves an operator-set tier alone unless recalculation is forced")
        void apply_whenManualOverride_skipsWrite() {
            CommercialParty party = party(AccountTier.BRONZE, true);
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(party));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("600000"))
                    .applyTier(true)
                    .build());

            assertThat(response.isTierApplied()).isFalse();
            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.PLATINUM);
            assertThat(party.getTier()).isEqualTo(AccountTier.BRONZE);
            verify(commercialPartyRepository, never()).save(any());
        }

        @Test
        @DisplayName("a forced recalculation overrides the operator and clears the override flag")
        void apply_whenForced_overridesAndClearsFlag() {
            CommercialParty party = party(AccountTier.BRONZE, true);
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(party));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("600000"))
                    .applyTier(true)
                    .forceRecalculation(true)
                    .build());

            assertThat(response.isTierApplied()).isTrue();
            assertThat(party.getTier()).isEqualTo(AccountTier.PLATINUM);
            // Left set, the flag would re-block the next automatic recalculation.
            assertThat(party.isTierManualOverride()).isFalse();
            verify(commercialPartyRepository).save(party);
        }

        @Test
        @DisplayName("reports the override as inactive once a forced recalculation has run")
        void apply_whenForced_reportsOverrideInactive() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.BRONZE, true)));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("600000"))
                    .applyTier(true)
                    .forceRecalculation(true)
                    .build());

            assertThat(response.isManualOverrideActive()).isFalse();
        }

        @Test
        @DisplayName("keeps the manual override flag when applying without forcing")
        void apply_withoutForce_keepsOverrideFlag() {
            CommercialParty party = party(AccountTier.STANDARD, false);
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(party));

            resolve(request()
                    .annualRevenue(new BigDecimal("600000"))
                    .applyTier(true)
                    .build());

            assertThat(party.isTierManualOverride()).isFalse();
        }

        @Test
        @DisplayName("reports the tier the account held before the call")
        void apply_reportsPreviousTierAsCurrent() {
            when(commercialPartyRepository.findById(ACCOUNT_ID))
                    .thenReturn(Optional.of(party(AccountTier.SILVER, false)));

            ResolveAccountTierResponse response = resolve(request()
                    .annualRevenue(new BigDecimal("600000"))
                    .applyTier(true)
                    .build());

            assertThat(response.getCurrentTier()).isEqualTo(AccountTier.SILVER);
            assertThat(response.getRecommendedTier()).isEqualTo(AccountTier.PLATINUM);
        }

        @Test
        @DisplayName("treats a null stored tier as STANDARD when reporting the current tier")
        void apply_whenStoredTierNull_reportsStandard() {
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(party(null, false)));

            assertThat(resolve(request().build()).getCurrentTier()).isEqualTo(AccountTier.STANDARD);
        }
    }

    @Nested
    @DisplayName("input validation")
    class InputValidation {

        @Test
        @DisplayName("rejects an account id that is not a UUID, naming the offending value")
        void resolve_whenAccountIdMalformed_throws() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resolve(ResolveAccountTierRequest.builder()
                            .accountId("not-a-uuid")
                            .build()))
                    .withMessageContaining("Invalid account ID format");
            verify(commercialPartyRepository, never()).findById(any());
        }

        @Test
        @DisplayName("rejects an unknown account")
        void resolve_whenAccountAbsent_throws() {
            when(commercialPartyRepository.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> resolve(request().build()))
                    .withMessageContaining("Account not found");
        }
    }
}
