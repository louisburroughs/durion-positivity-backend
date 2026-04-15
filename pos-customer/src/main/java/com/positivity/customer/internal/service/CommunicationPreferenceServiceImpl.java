package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.service.CommunicationPreferenceService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service for managing communication preferences and consent flags.
 *
 * <p>
 * Handles the business logic for retrieving and updating communication
 * preferences,
 * including:
 * </p>
 * <ul>
 * <li>Default OPT_OUT behavior for null preferences</li>
 * <li>Tracking update source (APP|API|ADMIN|IMPORT)</li>
 * <li>Optimistic locking via version field</li>
 * </ul>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/107">Backend
 *      Issue #107</a>
 */
@Service
public class CommunicationPreferenceServiceImpl implements CommunicationPreferenceService {
        private final Clock clock;

        private static final Logger log = LoggerFactory.getLogger(CommunicationPreferenceServiceImpl.class);

        private static final String DEFAULT_PREFERENCE = "OPT_OUT";
        private static final String DEFAULT_SOURCE = "APP";

        private final CommunicationPreferenceRepository preferenceRepository;
        private final CommercialPartyRepository partyRepository;

        public CommunicationPreferenceServiceImpl(
                        CommunicationPreferenceRepository preferenceRepository,
                        CommercialPartyRepository partyRepository,
                        Clock clock) {
                this.clock = clock;
                this.preferenceRepository = preferenceRepository;
                this.partyRepository = partyRepository;
        }

        /**
         * Get communication preferences for a party.
         *
         * <p>
         * If no preferences exist, returns defaults with all channels set to OPT_OUT.
         * </p>
         *
         * @param partyId the party ID
         * @return response containing preferences
         * @throws IllegalArgumentException if party not found or invalid ID
         */
        @Override
        @NonNull
        @Transactional(readOnly = true)
        public GetCommunicationPreferencesResponse getCommunicationPreferences(@NonNull UUID partyId) {
                // Verify party exists
                partyRepository
                                .findById(partyId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Party not found: " + partyId));

                // Get preferences or return defaults
                return preferenceRepository
                                .findByPartyId(partyId)
                                .map(this::mapToResponse)
                                .orElseGet(() -> createDefaultResponse(partyId));
        }

        /**
         * Create or update communication preferences for a party.
         *
         * <p>
         * Null preference values are interpreted as OPT_OUT.
         * </p>
         *
         * @param partyId the party ID
         * @param request the preferences to set
         * @return response with update status
         * @throws IllegalArgumentException if party not found or invalid data
         */
        @Override
        @NonNull
        @Transactional
        public UpsertCommunicationPreferencesResponse upsertCommunicationPreferences(
                        @NonNull UUID partyId, @NonNull UpsertCommunicationPreferencesRequest request) {

                // Verify party exists
                partyRepository
                                .findById(partyId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Party not found: " + partyId));

                // Find existing preferences or create new
                CommunicationPreference preference = preferenceRepository
                                .findByPartyId(partyId)
                                .orElseGet(() -> {
                                        CommunicationPreference newPref = new CommunicationPreference();
                                        newPref.setPartyId(partyId);
                                        return newPref;
                                });

                // Update fields from request, applying defaults for nulls
                preference.setEmailPreference(
                                request.getEmailPreference() != null ? request.getEmailPreference()
                                                : DEFAULT_PREFERENCE);
                preference.setSmsPreference(
                                request.getSmsPreference() != null ? request.getSmsPreference() : DEFAULT_PREFERENCE);
                preference.setPhonePreference(
                                request.getPhonePreference() != null ? request.getPhonePreference()
                                                : DEFAULT_PREFERENCE);
                preference.setMarketingPreference(
                                request.getMarketingPreference() != null ? request.getMarketingPreference()
                                                : DEFAULT_PREFERENCE);

                // Update consent flags
                if (request.getConsentFlags() != null) {
                        Map<String, Boolean> flags = new HashMap<>(request.getConsentFlags());
                        preference.setConsentFlags(flags);
                }

                if (request.getPreferencesNote() != null) {
                        preference.setPreferencesNote(request.getPreferencesNote());
                }

                // Set update source (default to APP if not provided)
                String updateSource = request.getUpdateSource() != null ? request.getUpdateSource() : DEFAULT_SOURCE;
                preference.setUpdateSource(updateSource);

                // Save and return response
                boolean isCreate = preference.getPreferenceId() == null;
                CommunicationPreference saved = preferenceRepository.save(preference);
                if (log.isInfoEnabled()) {
                        log.info(
                                        "Upserted communication preferences: partyId={}, operation={}, source={}",
                                        maskPartyId(partyId),
                                        isCreate ? "CREATE" : "UPDATE",
                                        updateSource.substring(0, Math.min(updateSource.length(), 5))); // Limit source

                }
                return UpsertCommunicationPreferencesResponse.builder()
                                .partyId(partyId.toString())
                                .version(String.valueOf(saved.getVersion()))
                                .operationType(isCreate ? "CREATED" : "UPDATED")
                                .status("SUCCESS")
                                .updatedAt(saved.getUpdatedAt().toString())
                                .build();
        }

        /**
         * Map CommunicationPreference entity to response DTO.
         *
         * @param preference the preference entity
         * @return response DTO
         */
        @NonNull
        private GetCommunicationPreferencesResponse mapToResponse(@NonNull CommunicationPreference preference) {
                return GetCommunicationPreferencesResponse.builder()
                                .partyId(preference.getPartyId().toString())
                                .version(String.valueOf(preference.getVersion()))
                                .emailPreference(preference.getEmailPreference())
                                .smsPreference(preference.getSmsPreference())
                                .phonePreference(preference.getPhonePreference())
                                .marketingPreference(preference.getMarketingPreference())
                                .consentFlags(
                                                preference.getConsentFlags() != null
                                                                ? new HashMap<>(preference.getConsentFlags())
                                                                : new HashMap<>())
                                .preferencesNote(preference.getPreferencesNote())
                                .updatedAt(
                                                preference.getUpdatedAt() != null
                                                                ? preference.getUpdatedAt().toString()
                                                                : null)
                                .updateSource(preference.getUpdateSource())
                                .build();
        }

        /**
         * Create a default response when no preferences exist.
         *
         * @param partyId the party ID
         * @return default response with OPT_OUT for all channels
         */
        @NonNull
        private GetCommunicationPreferencesResponse createDefaultResponse(@NonNull UUID partyId) {
                return GetCommunicationPreferencesResponse.builder()
                                .partyId(partyId.toString())
                                .version("0")
                                .emailPreference(DEFAULT_PREFERENCE)
                                .smsPreference(DEFAULT_PREFERENCE)
                                .phonePreference(DEFAULT_PREFERENCE)
                                .marketingPreference(DEFAULT_PREFERENCE)
                                .consentFlags(new HashMap<>())
                                .updatedAt(Instant.now(clock).toString())
                                .updateSource("DEFAULT")
                                .build();
        }

        private String maskPartyId(@NonNull UUID partyId) {
                String value = partyId.toString();
                return value.substring(0, 8) + "...";
        }
}
