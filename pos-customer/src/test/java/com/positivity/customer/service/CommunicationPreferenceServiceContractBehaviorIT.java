package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.customer.BaseContractIntegrationTest;
import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract behavior integration tests for Communication Preference Management
 * (CAP-090).
 * Tests verify:
 * - getCommunicationPreferences - Get preferences
 * - upsertCommunicationPreferences - Upsert preferences
 *
 * @author Durion Platform
 * @since CAP-090
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CommunicationPreferenceServiceContractBehaviorIT extends BaseContractIntegrationTest {

    private static final AtomicLong TEST_SEQUENCE = new AtomicLong(1L);

    @Autowired
    private CommunicationPreferenceService preferenceService;

    @Autowired
    private CommercialPartyRepository partyRepository;

    @Autowired
    private CommunicationPreferenceRepository preferenceRepository;

    private CommercialParty testParty;
    private UUID testPartyUuid;

    @BeforeEach
    void setUp() {
        long sequence = TEST_SEQUENCE.getAndIncrement();

        // Create test party
        testParty = new CommercialParty();
        testParty.setLegalName("Test Party");
        testParty.setDisplayName("Test Party");
        testParty.setCustomerNumber("CUST-TEST-" + sequence);
        testParty.setStatus(AccountStatus.ACTIVE);

        testParty = partyRepository.save(testParty);
        testPartyUuid = testParty.getPartyId();
    }

    // ========== getCommunicationPreferences ==========

    @Test
    @DisplayName("getCommunicationPreferences - Success: Returns default preferences when none exist")
    void getCommunicationPreferences_defaultPreferences() {
        GetCommunicationPreferencesResponse response =
                preferenceService.getCommunicationPreferences(testParty.getPartyId());

        assertThat(response.getPartyId()).isEqualTo(testPartyUuid.toString());
        assertThat(response.getEmailPreference()).isEqualTo("OPT_OUT");
        assertThat(response.getSmsPreference()).isEqualTo("OPT_OUT");
        assertThat(response.getPhonePreference()).isEqualTo("OPT_OUT");
        assertThat(response.getMarketingPreference()).isEqualTo("OPT_OUT");
        assertThat(response.getConsentFlags()).isEmpty();
    }

    @Test
    @DisplayName("getCommunicationPreferences - Success: Returns existing preferences")
    void getCommunicationPreferences_existingPreferences() {
        // Create preference
        CommunicationPreference preference = CommunicationPreference.builder()
                .partyId(testPartyUuid)
                .emailPreference("OPT_IN")
                .smsPreference("OPT_IN")
                .phonePreference("OPT_OUT")
                .marketingPreference("OPT_IN")
                .consentFlags(Map.of("marketing_email", true, "survey_sms", false))
                .build();
        preferenceRepository.save(preference);

        GetCommunicationPreferencesResponse response =
                preferenceService.getCommunicationPreferences(testParty.getPartyId());

        assertThat(response.getPartyId()).isEqualTo(testPartyUuid.toString());
        assertThat(response.getEmailPreference()).isEqualTo("OPT_IN");
        assertThat(response.getSmsPreference()).isEqualTo("OPT_IN");
        assertThat(response.getPhonePreference()).isEqualTo("OPT_OUT");
        assertThat(response.getMarketingPreference()).isEqualTo("OPT_IN");
        assertThat(response.getConsentFlags()).containsEntry("marketing_email", true);
        assertThat(response.getConsentFlags()).containsEntry("survey_sms", false);
    }

    @Test
    @DisplayName("getCommunicationPreferences - Not Found: Party does not exist")
    void getCommunicationPreferences_partyNotFound() {
        UUID missingPartyId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThatThrownBy(() -> preferenceService.getCommunicationPreferences(missingPartyId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Party not found");
    }

    // ========== upsertCommunicationPreferences ==========

    @Test
    @DisplayName("upsertCommunicationPreferences - Success: Creates new preferences")
    void upsertCommunicationPreferences_createNew() {
        UpsertCommunicationPreferencesRequest request = UpsertCommunicationPreferencesRequest.builder()
                .emailPreference("OPT_IN")
                .smsPreference("OPT_IN")
                .phonePreference("OPT_OUT")
                .marketingPreference("OPT_IN")
                .consentFlags(Map.of("marketing_email", true))
                .updateSource("customer_portal")
                .build();

        UpsertCommunicationPreferencesResponse response =
                preferenceService.upsertCommunicationPreferences(testParty.getPartyId(), request);

        assertThat(response.getPartyId()).isEqualTo(testPartyUuid.toString());
        assertThat(response.getOperationType()).isEqualTo("CREATED");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getUpdatedAt()).isNotNull();

        // Verify database state
        CommunicationPreference saved =
                preferenceRepository.findByPartyId(testPartyUuid).orElseThrow();
        assertThat(saved.getEmailPreference()).isEqualTo("OPT_IN");
        assertThat(saved.getUpdateSource()).isEqualTo("customer_portal");
    }

    @Test
    @DisplayName("upsertCommunicationPreferences - Success: Updates existing preferences")
    void upsertCommunicationPreferences_updateExisting() {
        // Create initial preference
        CommunicationPreference initial = CommunicationPreference.builder()
                .partyId(testPartyUuid)
                .emailPreference("OPT_OUT")
                .smsPreference("OPT_OUT")
                .phonePreference("OPT_OUT")
                .marketingPreference("OPT_OUT")
                .build();
        preferenceRepository.save(initial);

        UpsertCommunicationPreferencesRequest request = UpsertCommunicationPreferencesRequest.builder()
                .emailPreference("OPT_IN")
                .smsPreference("OPT_IN")
                .phonePreference("OPT_IN")
                .marketingPreference("OPT_IN")
                .consentFlags(Map.of("gdpr_consent", true))
                .updateSource("admin_portal")
                .build();

        UpsertCommunicationPreferencesResponse response =
                preferenceService.upsertCommunicationPreferences(testParty.getPartyId(), request);

        assertThat(response.getPartyId()).isEqualTo(testPartyUuid.toString());
        assertThat(response.getOperationType()).isEqualTo("UPDATED");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getUpdatedAt()).isNotNull();

        // Verify only one record exists
        assertThat(preferenceRepository.findByPartyId(testPartyUuid)).isPresent();
    }

    @Test
    @DisplayName("upsertCommunicationPreferences - Success: Defaults null preferences to OPT_OUT")
    void upsertCommunicationPreferences_defaultsNullToOptOut() {
        UpsertCommunicationPreferencesRequest request = UpsertCommunicationPreferencesRequest.builder()
                .emailPreference("OPT_IN")
                // sms, phone, marketing left null
                .build();

        UpsertCommunicationPreferencesResponse response =
                preferenceService.upsertCommunicationPreferences(testParty.getPartyId(), request);

        assertThat(response.getPartyId()).isEqualTo(testPartyUuid.toString());
        assertThat(response.getOperationType()).isEqualTo("CREATED");
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getUpdatedAt()).isNotNull();

        // Verify database state
        CommunicationPreference saved =
                preferenceRepository.findByPartyId(testPartyUuid).orElseThrow();
        assertThat(saved.getEmailPreference()).isEqualTo("OPT_IN");
        assertThat(saved.getSmsPreference()).isEqualTo("OPT_OUT");
        assertThat(saved.getPhonePreference()).isEqualTo("OPT_OUT");
        assertThat(saved.getMarketingPreference()).isEqualTo("OPT_OUT");
    }

    @Test
    @DisplayName("upsertCommunicationPreferences - Not Found: Party does not exist")
    void upsertCommunicationPreferences_partyNotFound() {
        UUID missingPartyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UpsertCommunicationPreferencesRequest request = UpsertCommunicationPreferencesRequest.builder()
                .emailPreference("OPT_IN")
                .build();

        assertThatThrownBy(() -> preferenceService.upsertCommunicationPreferences(missingPartyId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Party not found");
    }
}
